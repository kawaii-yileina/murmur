package com.im.core.cluster.message;

import com.elaina.chat.core.message.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 跨节点投递生产者：把消息发到目标节点的 Tag（NODE_{nodeId}）。
 *
 * 以 conversationId 作为队列选择键，保证同一会话在目标节点队列内有序。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "im.cluster.enabled", havingValue = "true")
public class ClusterMessageProducer {

    public static final String TOPIC = "IM_DISPATCH";
    public static final String TAG_PREFIX = "NODE_";

    private static final MessageQueueSelector QUEUE_SELECTOR = (queues, message, arg) ->
            queues.get(Math.floorMod(String.valueOf(arg).hashCode(), queues.size()));

    private final ObjectProvider<RocketMQTemplate> rocketMQTemplateProvider;
    private final ObjectMapper objectMapper;

    /** 投递到目标节点（seq 为会话权威序号，消费端据此附带 headers.seq） */
    public void dispatch(String targetNode, String conversationId, int chatType, long seq,
                         List<ClusterTarget> targets, ChatMessage message) {
        RocketMQTemplate template = rocketMQTemplateProvider.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("RocketMQTemplate 不可用，请确认 rocketmq.name-server 已配置");
        }
        ClusterMessage payload = new ClusterMessage(
                message.getId(), conversationId, chatType, seq, targetNode, targets, message);
        try {
            byte[] body = objectMapper.writeValueAsBytes(payload);
            Message mqMessage = new Message(TOPIC, tag(targetNode), conversationId, body);
            template.getProducer().send(mqMessage, QUEUE_SELECTOR, conversationId);
            log.debug("Cluster message dispatched: msgId={}, targetNode={}, targets={}",
                    message.getId(), targetNode, targets.size());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to dispatch message to node " + targetNode, e);
        }
    }

    public static String tag(String nodeId) {
        return TAG_PREFIX + nodeId;
    }
}
