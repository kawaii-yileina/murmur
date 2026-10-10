package com.im.core.chat.handler;

import com.elaina.chat.core.connection.Connection;
import com.elaina.chat.core.handler.HandlerChain;
import com.elaina.chat.core.handler.MessageHandler;
import com.elaina.chat.core.message.ChatMessage;
import com.im.core.cluster.ConnAttributes;
import com.im.core.cluster.config.ClusterProperties;
import com.im.core.cluster.route.RouteRegistry;
import com.im.core.online.service.OnlineStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatHandler implements MessageHandler {

    private static final int RENEW_TRACKER_MAX = 100_000;

    private final OnlineStatusService onlineStatusService;
    private final RouteRegistry routeRegistry;
    private final ClusterProperties clusterProperties;

    /** "userId:connId" -> 上次路由续期时间（毫秒），用于节流，避免每次心跳都打 Redis */
    private final ConcurrentHashMap<String, Long> lastRouteRenew = new ConcurrentHashMap<>();

    @Override
    public void handle(ChatMessage message, HandlerChain chain) {
        if ("HEARTBEAT".equals(message.getType())) {
            String userId = message.getFrom();
            if (userId != null && !userId.isEmpty()) {
                onlineStatusService.refreshHeartbeat(userId);
                renewRoute(userId, chain);
                log.debug("Heartbeat refreshed for user {}", userId);
            }
            return;
        }
        chain.doHandler(message);
    }

    /**
     * 集群模式下续期"本连接对应设备"的路由 TTL（仅写者本人可续期）。
     * 心跳按连接到达，用连接 id 作为节流键，天然支持多端各自续期自己在线的设备。
     */
    private void renewRoute(String userId, HandlerChain chain) {
        if (!clusterProperties.isEnabled()) {
            return;
        }
        Connection connection = chain.getContext().getConnection();
        if (connection == null) {
            return;
        }
        String throttleKey = userId + ":" + connection.id();
        long now = System.currentTimeMillis();
        long interval = clusterProperties.getHeartbeatRenewSeconds() * 1000;
        Long last = lastRouteRenew.get(throttleKey);
        if (last != null && now - last < interval) {
            return;
        }
        if (lastRouteRenew.size() > RENEW_TRACKER_MAX) {
            lastRouteRenew.clear();
        }
        lastRouteRenew.put(throttleKey, now);
        try {
            String deviceId = ConnAttributes.deviceId(connection);
            boolean renewed = routeRegistry.renew(userId, deviceId, connection.id());
            if (!renewed && connection.isActive()) {
                // 路由缺失/被清理（如 Redis 重启、TTL 到期）：重建（epoch INCR 天然幂等），
                // 避免该设备长期不在路由表，导致远端误判离线/收不到实时消息。
                log.warn("Route renew failed, re-register: user={}, device={}", userId, deviceId);
                routeRegistry.register(userId, deviceId, ConnAttributes.deviceType(connection),
                        connection.id(), ConnAttributes.platform(connection));
            }
        } catch (Exception e) {
            log.warn("Failed to renew route for user {}: {}", userId, e.getMessage());
        }
    }
}
