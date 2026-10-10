package com.im.core.cluster.message;

import com.elaina.chat.core.connection.Connection;
import com.elaina.chat.core.registry.UserConnectionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.core.cluster.NodeContext;
import com.im.core.message.service.OfflineMessageService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 跨节点投递消费者：只订阅本节点 Tag（NODE_{nodeId}），收到后直接回投本地连接。
 *
 * 处理顺序：检查是否已处理 -> 写入本地连接 -> 记录处理状态 -> 消费成功。
 * - 连接写入失败：不记录去重标记，返回 RECONSUME_LATER 触发重投（不丢）。
 * - 写入成功后、记录去重标记前进程崩溃：重投会导致重复，这是至少一次投递的正常边界。
 *   重复由稳定的消息 id（msgId）+ 客户端幂等 + 后续会话 seq 补拉共同兜底，不承诺"恰好一次"。
 * - 回投时目标已离线：写入离线队列，由用户重连时补投。
 * - 语义边界：writeAndFlush 返回仅代表已投递给连接，不代表客户端已收到；
 *   本阶段保证的是"消息持久化后可重新投递"，而非"客户端必定恰好收到一次"。
 * - 不重走 Dispatcher：避免回环与重复落库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "im.cluster.enabled", havingValue = "true")
public class ClusterMessageConsumer {

    private static final String DEDUP_PREFIX = "im:msg:dedup:";
    private static final Duration DEDUP_TTL = Duration.ofHours(24);

    private final UserConnectionRegistry connectionRegistry;
    private final OfflineMessageService offlineMessageService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final NodeContext nodeContext;

    @Value("${rocketmq.name-server}")
    private String nameServer;

    private DefaultMQPushConsumer consumer;

    @PostConstruct
    public void start() {
        String tag = ClusterMessageProducer.tag(nodeContext.getNodeId());
        String group = "im-dispatch-" + nodeContext.getNodeId();
        DefaultMQPushConsumer pushConsumer = new DefaultMQPushConsumer(group);
        pushConsumer.setNamesrvAddr(nameServer);
        pushConsumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            for (MessageExt message : messages) {
                try {
                    handle(message.getBody());
                } catch (Exception e) {
                    log.error("Failed to handle cluster message, will retry: {}", e.getMessage(), e);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        try {
            pushConsumer.subscribe(ClusterMessageProducer.TOPIC, tag);
            pushConsumer.start();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to start cluster message consumer (nameServer=" + nameServer + ", tag=" + tag + ")", e);
        }
        this.consumer = pushConsumer;
        log.info("Cluster message consumer started: group={}, tag={}, nameServer={}", group, tag, nameServer);
    }

    @PreDestroy
    public void stop() {
        if (consumer != null) {
            consumer.shutdown();
            log.info("Cluster message consumer stopped");
        }
    }

    private void handle(byte[] body) throws Exception {
        ClusterMessage payload = objectMapper.readValue(body, ClusterMessage.class);
        if (payload.getTargets() == null || payload.getMessage() == null) {
            return;
        }
        for (ClusterTarget target : payload.getTargets()) {
            String dedupKey = dedupKey(payload.getMsgId(), target.getDeviceId());
            // 1) 检查是否已处理（命中则跳过；不写标记）
            if (isProcessed(dedupKey)) {
                log.debug("Duplicate cluster message skipped: msgId={}, device={}",
                        payload.getMsgId(), target.getDeviceId());
                continue;
            }
            // 2) 写入本地连接（写入失败会抛异常，不会记录标记，交由 MQ 重投）
            Connection connection = connectionRegistry.getConnection(target.getUserId());
            if (connection != null && connection.isActive()) {
                connection.write(payload.getMessage());
            } else {
                offlineMessageService.storeOfflineMessage(target.getUserId(), payload.getMessage());
                log.debug("Target offline on this node, stored offline: user={}", target.getUserId());
            }
            // 3) 记录处理状态（写在投递之后，避免"标记后写失败"导致丢消息）
            markProcessed(dedupKey);
        }
    }

    private String dedupKey(String msgId, String deviceId) {
        return msgId == null ? null : DEDUP_PREFIX + msgId + ":" + deviceId;
    }

    /** 是否已处理过（无 msgId 的消息不去重） */
    private boolean isProcessed(String dedupKey) {
        return dedupKey != null && Boolean.TRUE.equals(redis.hasKey(dedupKey));
    }

    /** 记录处理状态；无 msgId 或标记写入失败时仅告警（重投可能重复，属至少一次边界） */
    private void markProcessed(String dedupKey) {
        if (dedupKey == null) {
            return;
        }
        redis.opsForValue().set(dedupKey, "1", DEDUP_TTL);
    }
}
