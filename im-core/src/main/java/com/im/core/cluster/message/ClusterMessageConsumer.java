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
 * - 幂等：im:msg:dedup:{msgId}:{deviceId} 保证同一消息同一设备只回投一次
 * - 回投失败（目标已离线）：写入离线队列，由用户重连时补投
 * - 不重走 Dispatcher：避免回环与重复落库
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
            if (!markIfNew(payload.getMsgId(), target.getDeviceId())) {
                log.debug("Duplicate cluster message skipped: msgId={}, device={}",
                        payload.getMsgId(), target.getDeviceId());
                continue;
            }
            Connection connection = connectionRegistry.getConnection(target.getUserId());
            if (connection != null && connection.isActive()) {
                connection.write(payload.getMessage());
            } else {
                offlineMessageService.storeOfflineMessage(target.getUserId(), payload.getMessage());
                log.debug("Target offline on this node, stored offline: user={}", target.getUserId());
            }
        }
    }

    /** 幂等：同一消息同一设备只回投一次 */
    private boolean markIfNew(String msgId, String deviceId) {
        if (msgId == null) {
            return true;
        }
        Boolean first = redis.opsForValue().setIfAbsent(DEDUP_PREFIX + msgId + ":" + deviceId, "1", DEDUP_TTL);
        return Boolean.TRUE.equals(first);
    }
}
