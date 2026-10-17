package com.im.core.call;

import com.elaina.chat.core.connection.Connection;
import com.elaina.chat.core.message.ChatMessage;
import com.elaina.chat.transport.util.ConnectionUtils;
import com.im.common.constant.ChatConstants;
import com.im.core.chat.registry.MultiConnectionRegistry;
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

/**
 * 通话信令路由：把瞬时信令投递到目标用户（本地直投 / 跨节点经 RocketMQ 定向投递）。
 *
 * 通话信令不落库、不进 Outbox/离线，故不能复用普通消息发送链路（持久化链路会直接跳过 CALL_*）。
 * 本组件按当前路由解析目标设备：本节点写连接；其他节点（存活）经 {@link ClusterMessageProducer} 定向投递；
 * 无路由（离线）则丢弃（由主叫振铃超时处理）。仅依赖路由/连接注册表与生产者，不依赖 MessageGateway（无成环风险）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallSignalRouter {

    private final ClusterProperties clusterProperties;
    private final RouteRegistry routeRegistry;
    private final MultiConnectionRegistry connectionRegistry;
    private final NodeContext nodeContext;
    private final ObjectProvider<ClusterMessageProducer> producerProvider;

    /** 投递到 msg.getTo() 的全部在线设备（对端用户） */
    public void deliver(ChatMessage msg) {
        if (msg == null || msg.getTo() == null || msg.getTo().isBlank()) {
            return;
        }
        String to = msg.getTo();
        try {
            if (!clusterProperties.isEnabled()) {
                for (Connection c : connectionRegistry.getConnections(to)) {
                    if (c != null && c.isActive()) {
                        ConnectionUtils.writeSafely(c, msg);
                    }
                }
                return;
            }
            String conversationId = "call:" + header(msg, ChatConstants.HEADER_CALL_ID);
            for (String deviceId : routeRegistry.deviceIds(to)) {
                RouteEntry entry = routeRegistry.find(to, deviceId);
                if (entry == null) {
                    continue;
                }
                if (nodeContext.getNodeId().equals(entry.getNodeId())) {
                    Connection c = connectionRegistry.getConnection(to, deviceId);
                    if (c != null && c.isActive()) {
                        ConnectionUtils.writeSafely(c, msg);
                    }
                } else if (routeRegistry.isNodeAlive(entry.getNodeId())) {
                    ClusterMessageProducer producer = producerProvider.getIfAvailable();
                    if (producer != null) {
                        producer.dispatch(entry.getNodeId(), conversationId, 0, 0L,
                                List.of(new ClusterTarget(to, deviceId, entry.getDeviceType())), msg);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Call signal delivery failed: type={}, to={}: {}", msg.getType(), to, e.getMessage());
        }
    }

    private String header(ChatMessage msg, String key) {
        if (msg.getHeaders() == null) {
            return "";
        }
        Object v = msg.getHeaders().get(key);
        return v == null ? "" : String.valueOf(v);
    }
}
