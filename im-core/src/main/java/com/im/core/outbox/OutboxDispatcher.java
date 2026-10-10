package com.im.core.outbox;

import com.elaina.chat.core.connection.Connection;
import com.elaina.chat.core.message.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.core.chat.registry.MultiConnectionRegistry;
import com.im.core.cluster.MessageDedupService;
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
 * - 会话内顺序由 OutboxMapper.selectClaimableIds 的会话窗口约束（同一会话在途未完成行少于 window）近似保证；
 *   最终顺序由客户端按 seq 排序 + 增量补拉兜底。
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
    private final MultiConnectionRegistry connectionRegistry;
    private final OfflineMessageService offlineMessageService;
    private final MessageDedupService dedupService;
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
            List<ClusterTarget> localTargets = new ArrayList<>();
            List<ClusterTarget> offlineTargets = new ArrayList<>();
            Set<String> offlineRecipients = new LinkedHashSet<>();
            for (String recipientId : recipients) {
                boolean routed = false;
                for (String deviceId : routeRegistry.deviceIds(recipientId)) {
                    RouteEntry entry = routeRegistry.find(recipientId, deviceId);
                    if (entry == null) {
                        continue;
                    }
                    boolean local = nodeContext.getNodeId().equals(entry.getNodeId());
                    // 非本节点：目标节点已失效（宕机/缩容且路由尚在 TTL 内）则跳过，避免投递到无消费者的节点。
                    // 本节点不做存活校验：即便节点存活标记因 Redis 抖动短暂缺失，本进程显然存活，应照常本地投递。
                    if (!local && !routeRegistry.isNodeAlive(entry.getNodeId())) {
                        continue;
                    }
                    routed = true;
                    if (local) {
                        localTargets.add(new ClusterTarget(recipientId, deviceId, entry.getDeviceType()));
                    } else {
                        remoteByNode.computeIfAbsent(entry.getNodeId(), key -> new ArrayList<>())
                                .add(new ClusterTarget(recipientId, deviceId, entry.getDeviceType()));
                    }
                }
                if (!routed) {
                    offlineRecipients.add(recipientId);
                }
            }

            // 本地写（按设备精确定位；发送时该目标恰在本节点）
            String msgId = message.getId();
            for (ClusterTarget target : localTargets) {
                if (dedupService.isProcessed(msgId, target.getUserId(), target.getDeviceId())) {
                    continue; // 本地直投已送达，跳过避免重复
                }
                Connection connection = connectionRegistry.getConnection(target.getUserId(), target.getDeviceId());
                if (connection != null && connection.isActive()) {
                    connection.write(message);
                    dedupService.markProcessed(msgId, target.getUserId(), target.getDeviceId());
                } else {
                    // 已知设备但本地连接不可用：按设备落离线
                    offlineTargets.add(target);
                }
            }

            // 远端按节点投递（任一节点发送失败 -> 整体重试，消费端按 msgId 幂等去重）
            for (Map.Entry<String, List<ClusterTarget>> entry : remoteByNode.entrySet()) {
                producer.dispatch(entry.getKey(), outbox.getConversationId(), message.isGroup() ? 1 : 0,
                        entry.getValue(), message);
            }

            // 离线：写入失败必须上抛，令整行重试（不得标记 DONE 而丢消息）
            for (String recipientId : offlineRecipients) {
                offlineMessageService.storeOfflineMessage(recipientId, message);
            }
            for (ClusterTarget target : offlineTargets) {
                offlineMessageService.storeOfflineMessage(target.getUserId(), target.getDeviceId(), message);
            }

            outboxService.markDone(outbox.getId());
        } catch (Exception e) {
            handleFailure(outbox, e);
        }
    }

    private void handleFailure(Outbox outbox, Exception e) {
        int retryCount = (outbox.getRetryCount() != null ? outbox.getRetryCount() : 0) + 1;
        // 仅记异常类型到日志（消息体可能出现在解析异常信息中，避免泄漏到日志）；详情落库 last_error（内部）
        String errType = e.getClass().getSimpleName();
        String err = e.getMessage();
        if (retryCount >= properties.getOutboxMaxRetry()) {
            log.error("Outbox {} exceeded max retry ({}), mark DEAD: {}", outbox.getId(), retryCount, errType);
            outboxService.markDead(outbox.getId(), retryCount, err);
        } else {
            long backoff = Math.min(properties.getOutboxBackoffBaseMillis() * (1L << Math.min(retryCount, 16)),
                    properties.getOutboxBackoffMaxMillis());
            LocalDateTime next = LocalDateTime.now().plusNanos(backoff * 1_000_000L);
            log.warn("Outbox {} dispatch failed (retry {}), backoff {}ms: {}", outbox.getId(), retryCount, backoff, errType);
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
