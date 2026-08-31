package com.im.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 生产级配置：将 Long 类型序列化为字符串。
 * MyBatis-Plus 雪花 ID 超过 JS 安全整数上限(2^53-1)，若以 number 返回前端会丢失精度，
 * 导致前端回传的 id 与原值不符。统一改为字符串传递。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(java.lang.Long.TYPE, ToStringSerializer.instance);
        };
    }
}
