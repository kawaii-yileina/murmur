package com.im.core.cluster.message;

import com.elaina.chat.core.connection.Connection;
import com.elaina.chat.core.message.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.common.constant.ChatConstants;
import com.im.core.chat.registry.MultiConnectionRegistry;
import com.im.core.cluster.ClusterStats;
import com.im.core.cluster.MessageDedupService;
import com.im.core.cluster.NodeContext;
import com.im.core.cluster.config.ClusterProperties;
import com.im.core.message.service.OfflineMessageService;
import com.im.core.message.util.SeqHeaders;
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
import org.springframework.stereotype.Component;

/**
 * 跨节点投递消费者：只订阅本节点 Tag（NODE_{nodeId}），收到后按设备回投本地连接。
 *
 * 处理顺序：检查是否已处理 -> 写入目标设备本地连接 -> 记录处理状态 -> 消费成功。
 * - 目标设备离线：按设备落离线队列，由该设备重连时补投。
 * - 连接写入为 best-effort（框架 write 非 active 时静默 no-op、不抛异常）：若写入瞬间连接恰好断开，
 *   可能"已标记去重但未送达"。该边界由客户端重连后的会话 seq 增量补拉兜底，属至少一次语义下的可接受边界。
 * - 达到最大重消费次数仍失败：作死信处理（尽力落离线 + 告警），不再无限重投。
 * - 不重走 Dispatcher：避免回环与重复落库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "im.cluster.enabled", havingValue = "true")
public class ClusterMessageConsumer {

    private final MultiConnectionRegistry connectionRegistry;
    private final OfflineMessageService offlineMessageService;
    private final MessageDedupService dedupService;
    private final ObjectMapper objectMapper;
    private final NodeContext nodeContext;
    private final ClusterProperties properties;
    private final ClusterStats clusterStats;

    @Value("${rocketmq.name-server}")
    private String nameServer;

    private DefaultMQPushConsumer consumer;

    @PostConstruct
    public void start() {
        String tag = ClusterMessageProducer.tag(nodeContext.getNodeId());
        String group = "im-dispatch-" + nodeContext.getNodeId();
        int maxReconsume = Math.max(1, properties.getConsumerMaxReconsume());
        DefaultMQPushConsumer pushConsumer = new DefaultMQPushConsumer(group);
        pushConsumer.setNamesrvAddr(nameServer);
        pushConsumer.setMaxReconsumeTimes(maxReconsume);
        pushConsumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            boolean retry = false;
            for (MessageExt message : messages) {
                try {
                    handle(message.getBody());
                    clusterStats.incConsumerReceived();
                } catch (Exception e) {
                    clusterStats.incConsumerFailure();
                    if (message.getReconsumeTimes() >= maxReconsume) {
                        // 达到最大重消费：作死信处理，落离线兜底并告警，避免无限重投
                        log.error("Cluster message dead-letter after {} reconsume: rocketMsgId={}, err={}",
                                message.getReconsumeTimes(), message.getMsgId(), e.getClass().getSimpleName());
                        persistDeadLetter(message.getBody());
                    } else {
                        log.warn("Failed to handle cluster message, will retry ({}): {}",
                                message.getReconsumeTimes(), e.getClass().getSimpleName());
                        retry = true;
                    }
                }
            }
            return retry ? ConsumeConcurrentlyStatus.RECONSUME_LATER : ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        try {
            pushConsumer.subscribe(ClusterMessageProducer.TOPIC, tag);
            pushConsumer.start();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to start cluster message consumer (nameServer=" + nameServer + ", tag=" + tag + ")", e);
        }
        this.consumer = pushConsumer;
        log.info("Cluster message consumer started: group={}, tag={}, nameServer={}, maxReconsume={}",
                group, tag, nameServer, maxReconsume);
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
        // 附带权威 seq（消费端投递路径）
        ChatMessage delivered = SeqHeaders.withSeq(payload.getMessage(), payload.getSeq());
        for (ClusterTarget target : payload.getTargets()) {
            // 1) 检查是否已处理（命中则跳过；不写标记）
            if (dedupService.isProcessed(payload.getMsgId(), target.getUserId(), target.getDeviceId())) {
                log.debug("Duplicate cluster message skipped: msgId={}, user={}, device={}",
                        payload.getMsgId(), target.getUserId(), target.getDeviceId());
                continue;
            }
            // 2) 写入目标设备本地连接（best-effort；序列化等前置异常会抛并交由 MQ 重投）
            Connection connection = connectionRegistry.getConnection(target.getUserId(), target.getDeviceId());
            if (connection != null && connection.isActive()) {
                connection.write(delivered);
            } else if (!ChatConstants.isCallSignal(delivered.getType())) {
                // 通话信令为瞬时消息：目标设备离线直接丢弃（不落离线，避免重连后幽灵来电）
                offlineMessageService.storeOfflineMessage(target.getUserId(), target.getDeviceId(), delivered);
                log.debug("Target offline on this node, stored offline: user={}, device={}",
                        target.getUserId(), target.getDeviceId());
            }
            // 3) 记录处理状态（写在投递之后，避免"标记后写失败"导致丢消息）
            dedupService.markProcessed(payload.getMsgId(), target.getUserId(), target.getDeviceId());
        }
    }

    /**
     * 死信兜底：尽力将尚未处理的目标消息落离线队列，避免静默丢失；失败仅告警（最终由会话 seq 补拉兜底）。
     * 与正常路径一致地检查/写入去重标记：前几轮已成功投递的目标不重复落离线，落库成功后写标记，
     * 避免同批次重投时重复入队。
     */
    private void persistDeadLetter(byte[] body) {
        try {
            ClusterMessage payload = objectMapper.readValue(body, ClusterMessage.class);
            if (payload.getMessage() == null || payload.getTargets() == null) {
                return;
            }
            ChatMessage delivered = SeqHeaders.withSeq(payload.getMessage(), payload.getSeq());
            for (ClusterTarget target : payload.getTargets()) {
                if (dedupService.isProcessed(payload.getMsgId(), target.getUserId(), target.getDeviceId())) {
                    continue;
                }
                if (!ChatConstants.isCallSignal(delivered.getType())) {
                    offlineMessageService.storeOfflineMessage(target.getUserId(), target.getDeviceId(), delivered);
                }
                dedupService.markProcessed(payload.getMsgId(), target.getUserId(), target.getDeviceId());
            }
        } catch (Exception e) {
            log.error("Failed to persist dead-letter cluster message to offline: {}", e.getClass().getSimpleName());
        }
    }
}
