package com.im.deploy;

import com.im.core.cluster.NodeDrainingEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 就绪态切换：节点开始排空时把 readiness 置为 REFUSING_TRAFFIC，
 * 令 Kubernetes 就绪探针失败、将本 Pod 从 Service Endpoints 摘除，停止接收新流量；
 * 之后才执行路由清理与连接排空。单机模式（未启用 cluster）不触发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DrainReadinessListener {

    private final ApplicationEventPublisher eventPublisher;

    @EventListener(NodeDrainingEvent.class)
    void onDraining(NodeDrainingEvent event) {
        AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.REFUSING_TRAFFIC);
        log.info("Readiness -> REFUSING_TRAFFIC (node draining: {})", event.nodeId());
    }
}
