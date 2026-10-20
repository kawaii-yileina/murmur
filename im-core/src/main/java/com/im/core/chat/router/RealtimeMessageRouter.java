package com.im.core.chat.router;

import com.elaina.chat.core.connection.Connection;
import com.elaina.chat.core.group.GroupMemberProvider;
import com.elaina.chat.core.message.ChatMessage;
import com.elaina.chat.transport.util.ConnectionUtils;
import com.im.common.constant.ChatConstants;
import com.im.core.chat.registry.MultiConnectionRegistry;
import com.im.core.cluster.ConnAttributes;
import com.im.core.cluster.NodeContext;
import com.im.core.cluster.config.ClusterProperties;
import com.im.core.cluster.message.ClusterMessageProducer;
import com.im.core.cluster.message.ClusterTarget;
import com.im.core.cluster.route.RouteEntry;
import com.im.core.cluster.route.RouteRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 瞬时消息路由：输入中、ACK、系统事件、顶号/封禁控制等不落库消息的统一出口。
 * 单机模式直接写本地连接；集群模式按 Redis 设备路由分发，本节点直写、其他节点经 RocketMQ 投递。
 * 瞬时消息不进入离线队列，目标设备离线时直接丢弃，由客户端重连后的会话增量同步兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RealtimeMessageRouter {

    private final ClusterProperties clusterProperties;
    private final RouteRegistry routeRegistry;
    private final MultiConnectionRegistry connectionRegistry;
    private final NodeContext nodeContext;
    private final GroupMemberProvider groupMemberProvider;
    private final ObjectProvider<ClusterMessageProducer> producerProvider;

    public void sendToUser(String userId, ChatMessage message) {
        sendToUser(userId, message, false);
    }

    public void sendToUser(String userId, ChatMessage message, boolean closeAfterWrite) {
        if (userId == null || userId.isBlank() || message == null) {
            return;
        }
        deliver(userId, userId, false, message, closeAfterWrite);
    }

    public void sendToUsers(List<String> userIds, ChatMessage message) {
        if (userIds == null || userIds.isEmpty() || message == null) {
            return;
        }
        for (String userId : userIds) {
            sendToUser(userId, message);
        }
    }

    public void sendToGroup(String groupId, ChatMessage message) {
        if (groupId == null || groupId.isBlank() || message == null) {
            return;
        }
        for (String memberId : groupMemberProvider.getMembers(groupId)) {
            deliver(memberId, groupId, true, message, false);
        }
    }

    /** 向用户全部在线设备下发控制消息，并在写入后关闭连接 */
    public void kickUser(String userId, ChatMessage message) {
        sendToUser(userId, message, true);
    }

    /** 只向指定设备下发并关闭连接，用于同类型设备顶号 */
    public void kickDevice(String userId, String deviceId, ChatMessage message) {
        if (userId == null || userId.isBlank() || deviceId == null || deviceId.isBlank() || message == null) {
            return;
        }
        ChatMessage outbound = prepare(userId, false, message, true);
        try {
            if (!clusterProperties.isEnabled()) {
                Connection connection = connectionRegistry.getConnection(userId, deviceId);
                write(connection, outbound, true);
                return;
            }
            RouteEntry entry = routeRegistry.find(userId, deviceId);
            if (entry == null) {
                return;
            }
            if (nodeContext.getNodeId().equals(entry.getNodeId())) {
                Connection connection = connectionRegistry.getConnection(userId, deviceId);
                write(connection, outbound, true);
            } else if (routeRegistry.isNodeAlive(entry.getNodeId())) {
                ClusterMessageProducer producer = producerProvider.getIfAvailable();
                if (producer != null) {
                    producer.dispatch(entry.getNodeId(), "control:" + userId, 0, 0L,
                            List.of(new ClusterTarget(userId, deviceId, entry.getDeviceType())), outbound);
                }
            }
        } catch (Exception e) {
            log.warn("Kick device delivery failed: user={}, device={}: {}", userId, deviceId, e.getClass().getSimpleName());
        }
    }

    private void deliver(String userId, String targetId, boolean group, ChatMessage message, boolean closeAfterWrite) {
        ChatMessage outbound = prepare(targetId, group, message, closeAfterWrite);
        try {
            if (!clusterProperties.isEnabled()) {
                writeLocal(userId, outbound, closeAfterWrite);
                return;
            }

            Set<String> routedDevices = ConcurrentHashMap.newKeySet();
            for (String deviceId : routeRegistry.deviceIds(userId)) {
                try {
                    RouteEntry entry = routeRegistry.find(userId, deviceId);
                    if (entry == null) {
                        continue;
                    }
                    routedDevices.add(deviceId);
                    if (nodeContext.getNodeId().equals(entry.getNodeId())) {
                        Connection connection = connectionRegistry.getConnection(userId, deviceId);
                        if (connection != null && connection.isActive()) {
                            write(connection, outbound, closeAfterWrite);
                        }
                    } else if (routeRegistry.isNodeAlive(entry.getNodeId())) {
                        ClusterMessageProducer producer = producerProvider.getIfAvailable();
                        if (producer == null) {
                            log.warn("Cluster producer unavailable, drop transient message: user={}, device={}",
                                    userId, deviceId);
                            continue;
                        }
                        producer.dispatch(entry.getNodeId(), "transient:" + userId, 0, 0L,
                                List.of(new ClusterTarget(userId, deviceId, entry.getDeviceType())), outbound);
                    }
                } catch (Exception e) {
                    // 单设备投递失败不阻断该用户其他设备
                    log.warn("Transient delivery failed for user={}, device={}: {}",
                            userId, deviceId, e.getClass().getSimpleName());
                }
            }

            // 路由尚未写入 Redis 的极短窗口内，本节点连接仍应收到消息
            for (Connection connection : connectionRegistry.getConnections(userId)) {
                String deviceId = ConnAttributes.deviceId(connection);
                if (!routedDevices.contains(deviceId)) {
                    write(connection, outbound, closeAfterWrite);
                }
            }
        } catch (Exception e) {
            log.warn("Realtime message delivery failed: user={}, type={}: {}",
                    userId, message.getType(), e.getClass().getSimpleName());
        }
    }

    private ChatMessage prepare(String targetId, boolean group, ChatMessage message, boolean closeAfterWrite) {
        Map<String, Object> headers = new ConcurrentHashMap<>(message.getHeaders());
        headers.put(ChatConstants.HEADER_TRANSIENT, "true");
        if (closeAfterWrite) {
            headers.put(ChatConstants.HEADER_CLOSE_AFTER_WRITE, "true");
        }
        String id = message.getId();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        ChatMessage outbound = ChatMessage.builder()
                .id(id)
                .type(message.getType())
                .from(message.getFrom())
                .to(targetId)
                .group(group)
                .content(message.getContent())
                .build();
        return outbound.withHeaders(headers);
    }

    private void writeLocal(String userId, ChatMessage message, boolean closeAfterWrite) {
        for (Connection connection : connectionRegistry.getConnections(userId)) {
            write(connection, message, closeAfterWrite);
        }
    }

    private void write(Connection connection, ChatMessage message, boolean closeAfterWrite) {
        if (connection == null || !connection.isActive()) {
            return;
        }
        ConnectionUtils.writeSafely(connection, message);
        if (closeAfterWrite) {
            CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS)
                    .execute(() -> ConnectionUtils.closeSafely(connection));
        }
    }
}
