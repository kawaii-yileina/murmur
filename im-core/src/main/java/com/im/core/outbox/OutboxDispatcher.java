package com.im.core.outbox;

import com.elaina.chat.core.connection.Connection;
import com.elaina.chat.core.message.ChatMessage;
import com.elaina.chat.core.registry.UserConnectionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.core.cluster.NodeContext;
import com.im.core.cluster.config.ClusterProperties;
import com.im.core.cluster.message.ClusterMessageProducer;
import com.im.core.cluster.message.ClusterTarget;
import com.im.core.cluster.route.RouteEntry;
import com.im.core.cluster.route.RouteRegistry;
import com.im.core.message.service.OfflineMessageService;
import com.im.core.outbox.dto.OutboxPayload;
import com.im.core.outbox.entity.Outbox;
import com.im.core.outbox.service.OutboxService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Outbox 投递器：短事务领取任务（PROCESSING + 租约）→ 事务外按当前路由发送 → 标记 DONE/重试。
 *
 * - 领取后发送在事务外进行，不持数据库行锁做网络 IO。
 * - 发送时重新解析路由，不依赖入队时的目标节点。
 * - 失败退避重试，超过上限置 DEAD；进程崩溃留下的 PROCESSING 由租约过期回收。
 * - DONE 仅表示 RocketMQ 发送成功，不代表客户端送达；重复由消费端幂等 + 客户端 msgId 去重收敛。
 * - 会话内顺序由 OutboxMapper.selectClaimableIds 的队头约束（同一会话只领取最早未完成行）保证。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "im.cluster.enabled", havingValue = "true")
public class OutboxDispatcher {

    private static final long ALERT_INTERVAL_MILLIS = 10_000;

    private final OutboxService outboxService;
    private final ClusterMessageProducer producer;
    private final RouteRegistry routeRegistry;
    private final NodeContext nodeContext;
    private final UserConnectionRegistry connectionRegistry;
    private final OfflineMessageService offlineMessageService;
    private final ObjectMapper objectMapper;
    private final ClusterProperties properties;

    private ScheduledExecutorService scheduler;
    private volatile long lastAlertAt = 0;

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-dispatcher");
            t.setDaemon(true);
            return t;
        });
        long interval = properties.getOutboxPollMillis();
        scheduler.scheduleWithFixedDelay(this::pollSafely, interval, interval, TimeUnit.MILLISECONDS);
        log.info("Outbox dispatcher started: poll={}ms, batch={}, lease={}s, maxRetry={}",
                interval, properties.getOutboxBatch(), properties.getOutboxLeaseSeconds(), properties.getOutboxMaxRetry());
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void pollSafely() {
        try {
            poll();
        } catch (Exception e) {
            log.error("Outbox poll error: {}", e.getMessage(), e);
        }
    }

    void poll() {
        List<Outbox> claimed = outboxService.claim(properties.getOutboxBatch(), properties.getOutboxLeaseSeconds());
        for (Outbox outbox : claimed) {
            dispatchOne(outbox);
        }
        alertBacklogIfNeeded();
    }

    private void dispatchOne(Outbox outbox) {
        try {
            OutboxPayload payload = objectMapper.readValue(outbox.getPayload(), OutboxPayload.class);
            ChatMessage message = payload.getMessage();
            List<String> recipients = payload.getRecipients();
            if (message == null || recipients == null || recipients.isEmpty()) {
                outboxService.markDone(outbox.getId());
                return;
            }

            // 重新解析当前路由
            Map<String, List<ClusterTarget>> remoteByNode = new LinkedHashMap<>();
            Set<String> localRecipients = new LinkedHashSet<>();
            Set<String> offlineRecipients = new LinkedHashSet<>();
            for (String recipientId : recipients) {
                boolean online = false;
                for (String deviceId : routeRegistry.deviceIds(recipientId)) {
                    RouteEntry entry = routeRegistry.find(recipientId, deviceId);
                    if (entry == null) {
                        continue;
                    }
                    online = true;
                    if (nodeContext.getNodeId().equals(entry.getNodeId())) {
                        localRecipients.add(recipientId);
                    } else {
                        remoteByNode.computeIfAbsent(entry.getNodeId(), key -> new ArrayList<>())
                                .add(new ClusterTarget(recipientId, deviceId, entry.getDeviceType()));
                    }
                }
                if (!online) {
                    offlineRecipients.add(recipientId);
                }
            }

            // 本地写（发送时该目标恰在本节点）
            for (String recipientId : localRecipients) {
                Connection connection = connectionRegistry.getConnection(recipientId);
                if (connection != null && connection.isActive()) {
                    connection.write(message);
                } else {
                    offlineRecipients.add(recipientId);
                }
            }

            // 远端按节点投递（任一节点发送失败 -> 整体重试，消费端按 msgId 幂等去重）
            for (Map.Entry<String, List<ClusterTarget>> entry : remoteByNode.entrySet()) {
                producer.dispatch(entry.getKey(), outbox.getConversationId(), message.isGroup() ? 1 : 0,
                        entry.getValue(), message);
            }

            // 离线
            for (String recipientId : offlineRecipients) {
                offlineMessageService.storeOfflineMessage(recipientId, message);
            }

            outboxService.markDone(outbox.getId());
        } catch (Exception e) {
            handleFailure(outbox, e);
        }
    }

    private void handleFailure(Outbox outbox, Exception e) {
        int retryCount = (outbox.getRetryCount() != null ? outbox.getRetryCount() : 0) + 1;
        String err = e.getMessage();
        if (retryCount >= properties.getOutboxMaxRetry()) {
            log.error("Outbox {} exceeded max retry ({}), mark DEAD: {}", outbox.getId(), retryCount, err);
            outboxService.markDead(outbox.getId(), retryCount, err);
        } else {
            long backoff = Math.min(properties.getOutboxBackoffBaseMillis() * (1L << Math.min(retryCount, 16)),
                    properties.getOutboxBackoffMaxMillis());
            LocalDateTime next = LocalDateTime.now().plusNanos(backoff * 1_000_000L);
            log.warn("Outbox {} dispatch failed (retry {}), backoff {}ms: {}", outbox.getId(), retryCount, backoff, err);
            outboxService.markRetry(outbox.getId(), retryCount, next, err);
        }
    }

    private void alertBacklogIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastAlertAt < ALERT_INTERVAL_MILLIS) {
            return;
        }
        lastAlertAt = now;
        try {
            long pending = outboxService.countPending();
            long dead = outboxService.countDead();
            if (dead > 0 || pending > properties.getOutboxBacklogAlertThreshold()) {
                log.warn("Outbox backlog alert: pendingDue={}, dead={}, oldestPending={}",
                        outboxService.countDuePending(), dead, outboxService.oldestPending());
            }
        } catch (Exception e) {
            log.debug("Outbox backlog check failed: {}", e.getMessage());
        }
    }
}
