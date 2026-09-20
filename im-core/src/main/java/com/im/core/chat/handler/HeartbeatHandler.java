package com.im.core.chat.handler;

import com.elaina.chat.core.handler.HandlerChain;
import com.elaina.chat.core.handler.MessageHandler;
import com.elaina.chat.core.message.ChatMessage;
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

    /** userId -> 上次路由续期时间（毫秒），用于节流，避免每次心跳都打 Redis */
    private final ConcurrentHashMap<String, Long> lastRouteRenew = new ConcurrentHashMap<>();

    @Override
    public void handle(ChatMessage message, HandlerChain chain) {
        if ("HEARTBEAT".equals(message.getType())) {
            String userId = message.getFrom();
            if (userId != null && !userId.isEmpty()) {
                onlineStatusService.refreshHeartbeat(userId);
                renewRoute(userId);
                log.debug("Heartbeat refreshed for user {}", userId);
            }
            return;
        }
        chain.doHandler(message);
    }

    /** 集群模式下按节流间隔续期该用户全部设备的路由 TTL */
    private void renewRoute(String userId) {
        if (!clusterProperties.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long interval = clusterProperties.getHeartbeatRenewSeconds() * 1000;
        Long last = lastRouteRenew.get(userId);
        if (last != null && now - last < interval) {
            return;
        }
        if (lastRouteRenew.size() > RENEW_TRACKER_MAX) {
            lastRouteRenew.clear();
        }
        lastRouteRenew.put(userId, now);
        try {
            routeRegistry.renewAll(userId);
        } catch (Exception e) {
            log.warn("Failed to renew route for user {}: {}", userId, e.getMessage());
        }
    }
}
