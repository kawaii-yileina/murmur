package com.im.core.cluster;

import com.im.core.cluster.route.RouteRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 节点存活心跳与优雅下线。
 *
 * 仅在 im.cluster.enabled=true 时生效：定时刷新 im:node:{nodeId} 的 TTL；
 * 进程关闭时清理本节点承载的全部连接路由，避免残留路由把消息投递到已下线节点。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "im.cluster.enabled", havingValue = "true")
public class NodeHeartbeatService {

    private final RouteRegistry routeRegistry;

    @PostConstruct
    public void start() {
        safeHeartbeat();
        log.info("Node heartbeat started");
    }

    @Scheduled(fixedDelayString = "${im.cluster.node-heartbeat-millis:10000}")
    public void beat() {
        safeHeartbeat();
    }

    @PreDestroy
    public void stop() {
        try {
            routeRegistry.removeNodeRoutes();
        } catch (Exception e) {
            log.warn("Failed to remove node routes on shutdown: {}", e.getMessage());
        }
    }

    private void safeHeartbeat() {
        try {
            routeRegistry.heartbeatNode();
        } catch (Exception e) {
            log.warn("Node heartbeat failed: {}", e.getMessage());
        }
    }
}
