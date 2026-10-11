package com.im.core.cluster.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分布式连接路由配置（前缀 im.cluster）。
 *
 * 默认 enabled=false：单机启动不依赖 RocketMQ，行为与未引入集群能力时一致；
 * 集群模式通过 application-cluster.yml 打开。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "im.cluster")
public class ClusterProperties {

    /** 分布式连接路由总开关 */
    private boolean enabled = false;

    /** 节点标识，多实例必须唯一 */
    private String nodeId = "connector-local-1";

    /** 连接路由表 TTL（秒） */
    private long routeTtlSeconds = 90;

    /** 路由 TTL 续期节流间隔（秒） */
    private long heartbeatRenewSeconds = 30;

    /** 默认设备类型（当前客户端为手机端） */
    private String defaultDeviceType = "mobile";

    /** 节点存活标记 TTL（秒） */
    private long nodeTtlSeconds = 30;

    /** 节点心跳间隔（毫秒） */
    private long nodeHeartbeatMillis = 10000;

    /** 优雅停机宽限期（毫秒）：先摘除节点存活标记，等待在途投递结束后再清理路由 */
    private long nodeShutdownGraceMillis = 5000;

    // ---------- Outbox ----------

    /** Outbox 每批领取数量 */
    private int outboxBatch = 200;

    /** 同一会话允许的在途（未 DONE）条数上限：限制窗内条数、保持近似顺序并保证吞吐 */
    private int outboxConversationWindow = 100;

    /** Outbox 任务租约（秒） */
    private long outboxLeaseSeconds = 30;

    /** Outbox 轮询间隔（毫秒） */
    private long outboxPollMillis = 500;

    /** Outbox 最大重试次数，超过置 DEAD */
    private int outboxMaxRetry = 8;

    /** Outbox 退避基数（毫秒） */
    private long outboxBackoffBaseMillis = 1000;

    /** Outbox 退避上限（毫秒） */
    private long outboxBackoffMaxMillis = 60000;

    /** 待处理积压告警阈值 */
    private int outboxBacklogAlertThreshold = 1000;

    // ---------- 消费端 ----------

    /** 跨节点消费最大重消费次数；超过则作死信处理（落离线兜底 + 告警），不再无限重投 */
    private int consumerMaxReconsume = 5;
}
