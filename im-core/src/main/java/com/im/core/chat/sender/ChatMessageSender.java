package com.im.core.chat.sender;

import com.elaina.chat.core.message.ChatMessage;
import com.elaina.chat.core.router.AbstractSender;
import com.elaina.chat.core.router.SendResult;
import com.elaina.chat.core.connection.Connection;
import com.elaina.chat.core.registry.UserConnectionRegistry;
import com.elaina.chat.core.offline.OfflineHandler;
import com.im.core.cluster.NodeContext;
import com.im.core.cluster.config.ClusterProperties;
import com.im.core.cluster.message.ClusterMessageProducer;
import com.im.core.cluster.message.ClusterTarget;
import com.im.core.cluster.route.RouteEntry;
import com.im.core.cluster.route.RouteRegistry;
import com.im.core.message.service.OfflineMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 消息发送器：本地连接直投；集群模式下目标在其他节点时按节点聚合转交（RocketMQ）；
 * 其余（无连接且无有效路由）落离线队列。
 */
@Slf4j
@Component
public class ChatMessageSender extends AbstractSender {

    private final OfflineMessageService offlineMessageService;
    private final RouteRegistry routeRegistry;
    private final ClusterProperties clusterProperties;
    private final NodeContext nodeContext;
    private final ObjectProvider<ClusterMessageProducer> clusterMessageProducerProvider;

    @Autowired
    public ChatMessageSender(UserConnectionRegistry registry, OfflineHandler offlineHandler,
                             OfflineMessageService offlineMessageService,
                             RouteRegistry routeRegistry, ClusterProperties clusterProperties,
                             NodeContext nodeContext,
                             ObjectProvider<ClusterMessageProducer> clusterMessageProducerProvider) {
        super(registry, offlineHandler);
        this.offlineMessageService = offlineMessageService;
        this.routeRegistry = routeRegistry;
        this.clusterProperties = clusterProperties;
        this.nodeContext = nodeContext;
        this.clusterMessageProducerProvider = clusterMessageProducerProvider;
    }

    @Override
    public SendResult send(ChatMessage message, List<String> recipients, Connection sender) {
        List<String> delivered = new ArrayList<>();
        List<String> offline = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        if (recipients == null || recipients.isEmpty()) {
            return SendResult.of(delivered, offline, failed);
        }

        Map<String, List<ClusterTarget>> remoteByNode =
                clusterProperties.isEnabled() ? new LinkedHashMap<>() : null;

        for (String recipientId : recipients) {
            if (recipientId == null) {
                continue;
            }
            Connection conn = registry.getConnection(recipientId);
            if (conn != null && conn.isActive()) {
                conn.write(message);
                delivered.add(recipientId);
                continue;
            }
            if (remoteByNode != null && routeToRemote(recipientId, remoteByNode)) {
                // 已转交目标节点：由该节点消费后回投或落离线（at-least-once）
                delivered.add(recipientId);
                continue;
            }
            offline.add(recipientId);
            offlineMessageService.storeOfflineMessage(recipientId, message);
            notifyOffline(message, recipientId, sender);
        }

        if (remoteByNode != null && !remoteByNode.isEmpty()) {
            dispatchRemote(message, remoteByNode);
        }

        return SendResult.of(delivered, offline, failed);
    }

    /** 目标不在本节点：按设备解析路由并按目标节点聚合。返回是否已转交远端 */
    private boolean routeToRemote(String recipientId, Map<String, List<ClusterTarget>> remoteByNode) {
        boolean routed = false;
        Set<String> deviceIds = routeRegistry.deviceIds(recipientId);
        for (String deviceId : deviceIds) {
            RouteEntry entry = routeRegistry.find(recipientId, deviceId);
            if (entry == null || nodeContext.getNodeId().equals(entry.getNodeId())) {
                continue;
            }
            remoteByNode.computeIfAbsent(entry.getNodeId(), key -> new ArrayList<>())
                    .add(new ClusterTarget(recipientId, deviceId, entry.getDeviceType()));
            routed = true;
        }
        return routed;
    }

    private void dispatchRemote(ChatMessage message, Map<String, List<ClusterTarget>> remoteByNode) {
        ClusterMessageProducer producer = clusterMessageProducerProvider.getIfAvailable();
        if (producer == null) {
            log.error("Cluster producer unavailable, dropping remote dispatch for message {}", message.getId());
            return;
        }
        String conversationId = conversationId(message);
        int chatType = message.isGroup() ? 1 : 0;
        for (Map.Entry<String, List<ClusterTarget>> entry : remoteByNode.entrySet()) {
            try {
                producer.dispatch(entry.getKey(), conversationId, chatType, entry.getValue(), message);
            } catch (Exception e) {
                log.error("Failed to dispatch message {} to node {}: {}",
                        message.getId(), entry.getKey(), e.getMessage());
            }
        }
    }

    /** 会话标识：私聊按双方 id 排序保证两端一致，群聊为群 id */
    private String conversationId(ChatMessage message) {
        if (message.isGroup()) {
            return "group:" + message.getTo();
        }
        String from = message.getFrom();
        String to = message.getTo();
        if (from == null || to == null) {
            return "private:" + from + ":" + to;
        }
        return from.compareTo(to) <= 0
                ? "private:" + from + ":" + to
                : "private:" + to + ":" + from;
    }
}
