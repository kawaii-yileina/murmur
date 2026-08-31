package com.im.hermes.client;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "hermes")
public class HermesProperties {
    private boolean enabled = false;
    private String apiType = "openai";
    private String baseUrl = "http://localhost:8642/v1";
    private String apiKey = "change-me-local-dev";
    private String model = "hermes-agent";
    private Integer maxTokens = 4096;
    private Double temperature = 0.7;
    private Long sessionTimeout = 3600L;
    /**
     * true  = 使用 Hermes 原生会话 API（/api/sessions/{id}/chat/stream，Hermes 管理会话上下文）
     * false = 使用 OpenAI 兼容 API（/v1/chat/completions，本地 MySQL 拼全量历史）—— 降级方案
     */
    private boolean nativeApi = true;
}