package com.im.core.cluster.config;

import com.im.core.cluster.NodeContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 集群能力装配。
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
