package com.im.metrics;

import com.im.core.chat.registry.MultiConnectionRegistry;
import com.im.core.cluster.ClusterStats;
import com.im.core.outbox.service.OutboxService;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 集群指标绑定：把运行计数（ClusterStats）与周期性采样值（Outbox 积压、本地在线连接数）
 * 绑定到 Micrometer，经 /actuator/metrics 暴露。仅集群模式启用。
 *
 * 统一 node 标签由配置 `management.metrics.tags.node` 提供（对全部 meter 生效，含 JVM/HTTP）。
 * 指标：
 * - im.dispatch.success/failure/dead  本节点 Outbox 投递结果计数
 * - im.consumer.received/failure      本节点跨节点消费结果计数
 * - im.offline.stored                 本节点离线入队计数
 * - im.outbox.pending/dead            Outbox 待处理/死信行数（采样）
 * - im.connections.online             本节点在线连接数（采样）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "im.cluster.enabled", havingValue = "true")
public class ClusterMetricsBinder {

    private final MeterRegistry registry;
    private final ClusterStats stats;
    private final OutboxService outboxService;
    private final MultiConnectionRegistry connectionRegistry;

    private final AtomicLong outboxPending = new AtomicLong();
    private final AtomicLong outboxDead = new AtomicLong();
    private final AtomicLong onlineConnections = new AtomicLong();

    @PostConstruct
    void bind() {
        FunctionCounter.builder("im.dispatch.success", stats, ClusterStats::getDispatchSuccess).register(registry);
        FunctionCounter.builder("im.dispatch.failure", stats, ClusterStats::getDispatchFailure).register(registry);
        FunctionCounter.builder("im.dispatch.dead", stats, ClusterStats::getDispatchDead).register(registry);
        FunctionCounter.builder("im.consumer.received", stats, ClusterStats::getConsumerReceived).register(registry);
        FunctionCounter.builder("im.consumer.failure", stats, ClusterStats::getConsumerFailure).register(registry);
        FunctionCounter.builder("im.offline.stored", stats, ClusterStats::getOfflineStored).register(registry);
        Gauge.builder("im.outbox.pending", outboxPending, AtomicLong::doubleValue).register(registry);
        Gauge.builder("im.outbox.dead", outboxDead, AtomicLong::doubleValue).register(registry);
        Gauge.builder("im.connections.online", onlineConnections, AtomicLong::doubleValue).register(registry);
        sample();
    }

    @Scheduled(fixedDelayString = "${im.cluster.metrics-interval-millis:15000}")
    void sample() {
        set(outboxPending, outboxService::countPending);
        set(outboxDead, outboxService::countDead);
        set(onlineConnections, connectionRegistry::connectionCount);
    }

    private void set(AtomicLong target, LongSupplier supplier) {
        try {
            target.set(supplier.getAsLong());
        } catch (Exception e) {
            log.debug("Cluster metrics sample failed: {}", e.getMessage());
        }
    }
}
