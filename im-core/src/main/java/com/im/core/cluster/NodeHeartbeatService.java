package com.im.core.cluster;

import com.im.core.cluster.config.ClusterProperties;
import com.im.core.cluster.route.RouteRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 节点存活心跳与优雅下线。
 *
 * 仅在 im.cluster.enabled=true 时生效：定时刷新 im:node:{nodeId} 的 TTL。
 * 优雅下线顺序：先摘除存活标记（令其他节点停止向本节点投递）→ 等待宽限期（在途投递完成）→ 清理本节点路由与标记。
 * 停机期间心跳停止，避免标记被重新写回。
 *
 * 用 ContextClosedEvent 而非 @PreDestroy：容器关闭时先发布该事件（此时 Redis 仍可用）再停止 SmartLifecycle(Lettuce)，
 * 而 @PreDestroy 阶段 Redis 连接工厂已停止，会导致下线清理失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "im.cluster.enabled", havingValue = "true")
public class NodeHeartbeatService {

    private final RouteRegistry routeRegistry;
    private final ClusterProperties properties;

    private volatile boolean shuttingDown = false;

    @PostConstruct
    public void start() {
        safeHeartbeat();
        log.info("Node heartbeat started");
    }

    @Scheduled(fixedDelayString = "${im.cluster.node-heartbeat-millis:10000}")
    public void beat() {
        if (shuttingDown) {
            return;
        }
        safeHeartbeat();
    }

    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        shuttingDown = true;
        try {
            routeRegistry.markNodeDraining();
        } catch (Exception e) {
            log.warn("Failed to mark node draining on shutdown: {}", e.getMessage());
        }
        // 硬上限 10s：避免 Redis 抖动/超时把容器关闭拖过编排器宽限期而被强杀（此时路由清理未执行）
        long grace = Math.min(properties.getNodeShutdownGraceMillis(), 10_000);
        if (grace > 0) {
            try {
                Thread.sleep(grace);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
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
