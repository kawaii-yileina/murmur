package com.im.hermes.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Hermes 原生会话 API 客户端
 *
 * 对接 Hermes 原生端点（区别于 OpenAI 兼容的 /v1/chat/completions）：
 *   POST   /api/sessions/{id}/chat/stream   流式对话（SSE）
 *   POST   /api/sessions                    创建会话
 *   DELETE /api/sessions/{id}               删除会话
 *   GET    /v1/models                       模型列表
 *   POST   /api/sessions/{id}/model         锁定会话模型
 *
 * 流式返回：Flux 的每个元素是合并后的 JSON 字符串 {"event":"...","data":{...}}，
 * 由 Controller 以 text/event-stream 转发给前端（Spring 自动包装为 data: 行）。
 *
 * baseUrl 说明：兼容用户配置带 /v1 后缀的地址（OpenAI 格式习惯），
 * 内部统一去掉 /v1 后再拼接原生路径。
 */
@Slf4j
@Component
public class HermesNativeClient {

    private final ObjectMapper objectMapper = new ObjectMapper();
    // ─── 流式对话 ───

    public Flux<String> chatStream(String sessionId, Object message,
                                   String baseUrl, String apiKey,
                                   String model, String provider) {
        WebClient webClient = buildWebClient(baseUrl, apiKey);

        Map<String, Object> body = new HashMap<>();
        body.put("message", message);
        if (model != null && !model.isBlank()) body.put("model", model);
        if (provider != null && !provider.isBlank()) body.put("provider", provider);

        // DEBUG: log request body
        try {
            log.info("[HermesNativeClient] chatStream request: sessionId={}, body={}", sessionId, objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            log.info("[HermesNativeClient] chatStream request: sessionId={}, body={}", sessionId, body);
        }

        return webClient.post()
                .uri("/api/sessions/{id}/chat/stream", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        resp -> resp.bodyToMono(String.class).map(errBody -> {
                            log.error("[HermesNativeClient] chatStream error: status={}, body={}", resp.statusCode(), errBody);
                            return new RuntimeException("Hermes API error " + resp.statusCode() + ": " + errBody);
                        }))
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .map(this::toCombinedJson)
                .filter(Objects::nonNull);
    }

    /**
     * 将 Hermes 的 SSE 事件（event + data 两行）合并为单个 JSON：
     * {"event":"assistant.delta","data":{...}}
     * data 不是合法 JSON 时按纯文本包装，保证前端始终能解析。
     */
    private String toCombinedJson(ServerSentEvent<String> sse) {
        String event = sse.event() != null ? sse.event() : "message";
        String data = sse.data();
        if (data == null || data.isBlank()) return null;
        try {
            ObjectNode combined = objectMapper.createObjectNode();
            combined.put("event", event);
            combined.set("data", objectMapper.readTree(data));
            return objectMapper.writeValueAsString(combined);
        } catch (Exception e) {
            ObjectNode combined = objectMapper.createObjectNode();
            combined.put("event", event);
            combined.put("data", data);
            return combined.toString();
        }
    }

    // ─── 会话生命周期（均为 best-effort，失败不阻塞主流程）───

    public void createSession(String sessionId, String title, String systemPrompt,
                              String model, String baseUrl, String apiKey) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("id", sessionId);
            if (title != null && !title.isBlank()) body.put("title", title);
            if (systemPrompt != null && !systemPrompt.isBlank()) body.put("system_prompt", systemPrompt);
            if (model != null && !model.isBlank()) body.put("model", model);
            buildWebClient(baseUrl, apiKey).post()
                    .uri("/api/sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("Hermes session created: {}", sessionId);
        } catch (Exception e) {
            log.warn("createSession failed for {}: {}", sessionId, rootMessage(e));
        }
    }

    public void deleteSession(String sessionId, String baseUrl, String apiKey) {
        try {
            buildWebClient(baseUrl, apiKey).delete()
                    .uri("/api/sessions/{id}", sessionId)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("Hermes session deleted: {}", sessionId);
        } catch (Exception e) {
            log.debug("deleteSession best-effort skipped for {}: {}", sessionId, rootMessage(e));
        }
    }

    // ─── 模型管理 ───

    /** 返回 /v1/models 的原始 JSON（data 数组，含 hermes-agent 别名） */
    public JsonNode getModels(String baseUrl, String apiKey) {
        try {
            String body = buildWebClient(baseUrl, apiKey).get()
                    .uri("/v1/models")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return body != null ? objectMapper.readTree(body).path("data") : null;
        } catch (Exception e) {
            log.warn("getModels failed: {}", rootMessage(e));
            return null;
        }
    }

    /**
     * 返回 /api/model/options 的完整 provider/model 目录（真实 LLM 列表，
     * 与 Hermes 桌面端模型选择器一致，不含 hermes-agent 别名）。
     * 结构取决于用户 Hermes 配置（providers/models/defaults 字段），原样返回。
     */
    public JsonNode getModelCatalog(String baseUrl, String apiKey) {
        try {
            String body = buildWebClient(baseUrl, apiKey).get()
                    .uri("/api/model/options")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return body != null ? objectMapper.readTree(body) : null;
        } catch (Exception e) {
            log.warn("getModelCatalog failed: {}", rootMessage(e));
            return null;
        }
    }

    public void lockModel(String sessionId, String model, String provider, String baseUrl, String apiKey) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            if (provider != null && !provider.isBlank()) body.put("provider", provider);
            buildWebClient(baseUrl, apiKey).post()
                    .uri("/api/sessions/{id}/model", sessionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (Exception e) {
            log.warn("lockModel failed for {}: {}", sessionId, rootMessage(e));
        }
    }

    // ─── 内部工具 ───

    private WebClient buildWebClient(String baseUrl, String apiKey) {
        return WebClient.builder()
                .baseUrl(normalizeBaseUrl(baseUrl))
                .defaultHeader("Authorization", "Bearer " + (apiKey != null ? apiKey : ""))
                .defaultHeader("Content-Type", "application/json")
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    /** 用户配置可能带 /v1 后缀（OpenAI 兼容格式习惯），原生 API 需要去掉 */
    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return "http://localhost:8642";
        return baseUrl.replaceAll("/v1/?$", "");
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null) cur = cur.getCause();
        return cur.getMessage();
    }
}
