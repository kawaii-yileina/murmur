package com.im.storage.impl.s3;

import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * S3 兼容对象存储客户端（如 SeaweedFS）。
 * 采用 S3 协议客户端对接，存储实现与具体服务端无关。
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "storage.type", havingValue = "s3")
public class S3Config {

    private final S3Properties properties;

    @Bean
    public MinioClient s3Client() {
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }
}
