package com.im.core.cluster.config;

import com.im.core.cluster.NodeContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 集群能力装配。
 *
 * 本阶段（B1）只提供节点上下文；路由表、跨节点生产者/消费者在后续批次加入。
 * 注意：集群组件位于框架 Dispatcher 链路上，不得依赖 MessageGateway（避免循环依赖）。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(ClusterProperties.class)
public class ClusterConfiguration {

    @Bean
    public NodeContext nodeContext(ClusterProperties properties) {
        NodeContext context = new NodeContext(properties.getNodeId(), properties.getDefaultDeviceType());
        log.info("Cluster node context initialized: nodeId={}, clusterEnabled={}",
                context.getNodeId(), properties.isEnabled());
        return context;
    }
}
