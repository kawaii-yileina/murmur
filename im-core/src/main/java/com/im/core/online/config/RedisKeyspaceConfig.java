package com.im.core.online.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisKeyspaceConfig {

    private final RedisConnectionFactory connectionFactory;

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer() {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }

    @Bean
    public String enableKeyspaceNotifications() {
        try (var connection = connectionFactory.getConnection()) {
            connection.setConfig("notify-keyspace-events", "Ex");
            log.info("Redis keyspace notifications enabled (Ex)");
        } catch (Exception e) {
            log.warn("Failed to enable Redis keyspace notifications: {}", e.getMessage());
        }
        return "ok";
    }
}
