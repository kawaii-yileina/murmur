package com.im.hermes.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.im.common.model.Result;
import com.im.hermes.client.HermesNativeClient;
import com.im.hermes.client.HermesProperties;
import com.im.hermes.model.AiConversation;
import com.im.hermes.model.AiConversationMapper;
import com.im.hermes.model.UserAiConfig;
import com.im.hermes.service.HermesService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class HermesController {

    private final HermesService hermesService;
    private final AiConversationMapper aiConversationMapper;
    private final HermesNativeClient hermesNativeClient;
    private final HermesProperties hermesProperties;
    private final com.im.hermes.security.SsrfGuard ssrfGuard;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    // ─── 对话 ───

    @PostMapping("/chat")
    public Result<String> chat(@RequestBody Map<String, String> request,
                               @RequestAttribute("userId") String userId) {
        String sessionId = request.getOrDefault("sessionId", "ai_" + userId);
        try {
            String response = hermesService.chat(userId, request.get("message"), sessionId);
            return Result.ok(response);
        } catch (Exception e) {
            return Result.error("AI服务无法连接，请检查AI配置(基础地址/API Key)是否正确");
        }
    }

    /**
     * 流式对话：转发 Hermes SSE 事件流。
     * 每个元素是合并后的 JSON：{"event":"assistant.delta","data":{...}}
     * Spring 以 text/event-stream 自动包装为 data: 行发送。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody Map<String, String> request,
                                   @RequestAttribute("userId") String userId) {
        String sessionId = request.getOrDefault("sessionId", "ai_" + userId);
        String message = request.get("message");
        if (message == null || message.isBlank()) {
            return Flux.just("{\"event\":\"error\",\"data\":{\"message\":\"消息内容不能为空\"}}");
        }
        return hermesService.chatStream(userId, message, sessionId);
    }

    // ─── 会话管理 ───

    @GetMapping("/sessions")
    public Result<List<Map<String, Object>>> getSessions(@RequestAttribute("userId") String userId) {
        return Result.ok(hermesService.getSessions(userId));
    }

    /**
     * 会话详情（转发 Hermes GET /api/sessions/{id}）：
     * 前端用于回显当前锁定的模型与会话标题。
     */
    @GetMapping("/sessions/{sessionId}/detail")
    public Result<Map<String, Object>> getSessionDetail(
            @RequestAttribute("userId") String userId,
            @PathVariable String sessionId) {
        if (!hermesProperties.isNativeApi()) {
            return Result.ok(Map.of());
        }
        UserAiConfig config = hermesService.getUserConfig(userId);
        String baseUrl = config != null && config.getBaseUrl() != null
                ? config.getBaseUrl() : hermesProperties.getBaseUrl();
        String apiKey = config != null && config.getApiKey() != null
                ? config.getApiKey() : hermesProperties.getApiKey();
        JsonNode session = hermesNativeClient.getSessionDetail(sessionId, baseUrl, apiKey);
        if (session == null || session.isMissingNode() || session.isNull()) {
            return Result.ok(Map.of());
        }
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("sessionId", sessionId);
        data.put("model", session.path("model").asText(null));
        data.put("title", session.path("title").asText(null));
        return Result.ok(data);
    }

    @GetMapping("/sessions/{sessionId}/history")
    public Result<List<AiConversation>> getSessionHistory(
            @RequestAttribute("userId") String userId,
            @PathVariable String sessionId) {
        List<AiConversation> history = aiConversationMapper.findByUserIdAndSessionId(
            Long.parseLong(userId), sessionId
        );
        return Result.ok(history);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(
            @RequestAttribute("userId") String userId,
            @PathVariable String sessionId) {
        hermesService.clearSession(userId, sessionId);
        return Result.ok();
    }

    /**
     * 截断消息：保留该会话按时间序的前 keep 条，删除其后所有消息。
     * 用于"编辑重发 / 重新生成 / 重新发送"的覆盖语义。
     */
    @DeleteMapping("/sessions/{sessionId}/messages")
    public Result<Void> truncateMessages(
            @RequestAttribute("userId") String userId,
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "0") int keep) {
        hermesService.truncateMessagesFrom(userId, sessionId, keep);
        return Result.ok();
    }

    /**
     * 显式创建 Hermes 会话（app 侧新建 AI 会话时调用，best-effort）。
     * 支持标题 / 系统提示词 / 初始模型。若 Hermes 会话已存在（409），视为成功。
     */
    @PostMapping("/sessions")
    public Result<Void> createSession(@RequestBody Map<String, String> request,
                                      @RequestAttribute("userId") String userId) {
        if (!hermesProperties.isNativeApi()) {
            return Result.ok();
        }
        String sessionId = request.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            return Result.error("sessionId is required");
        }
        UserAiConfig config = hermesService.getUserConfig(userId);
        String baseUrl = config != null && config.getBaseUrl() != null
                ? config.getBaseUrl() : hermesProperties.getBaseUrl();
        String apiKey = config != null && config.getApiKey() != null
                ? config.getApiKey() : hermesProperties.getApiKey();
        hermesNativeClient.createSession(sessionId, request.get("title"),
                request.get("systemPrompt"), request.get("model"), baseUrl, apiKey);
        // 标题 + 系统提示词落库：标题用于会话列表/聊天页展示；系统提示词
        // 因 Hermes 侧首条消息（conversation_history 为空时）不会从会话行加载，
        // 需要每次对话以 system_message 显式传入
        hermesService.saveSessionMeta(userId, sessionId, request.get("title"), request.get("systemPrompt"));
        return Result.ok();
    }

    /**
     * 编辑 AI（assistant）消息内容（覆盖 MySQL 展示副本与 Hermes 侧上下文）。
     * messageId 为 MySQL 侧 id，服务端负责映射到 Hermes 侧消息并校验一致性。
     */
    @PatchMapping("/sessions/{sessionId}/messages/{messageId}")
    public Result<Void> editMessage(@RequestAttribute("userId") String userId,
                                    @PathVariable String sessionId,
                                    @PathVariable long messageId,
                                    @RequestBody Map<String, String> request) {
        String content = request.get("content");
        if (content == null || content.isBlank()) {
            return Result.error("消息内容不能为空");
        }
        hermesService.editSessionMessage(userId, sessionId, messageId, content);
        return Result.ok();
    }

    // ─── 模型管理 ───

    @GetMapping("/models")
    public Result<List<Map<String, Object>>> getModels(@RequestAttribute("userId") String userId) {
        if (!hermesProperties.isNativeApi()) {
            return Result.ok(List.of());
        }
        UserAiConfig config = hermesService.getUserConfig(userId);
        String baseUrl = config != null && config.getBaseUrl() != null
                ? config.getBaseUrl() : hermesProperties.getBaseUrl();
        String apiKey = config != null && config.getApiKey() != null
                ? config.getApiKey() : hermesProperties.getApiKey();

        // 1. 完整目录（/api/model/options）—— 真实大模型列表
        JsonNode catalog = hermesNativeClient.getModelCatalog(baseUrl, apiKey);
        List<Map<String, Object>> result = extractModelsFromCatalog(catalog);
        if (!result.isEmpty()) {
            return Result.ok(result);
        }

        // 2. 降级：/v1/models
        JsonNode models = hermesNativeClient.getModels(baseUrl, apiKey);
        if (models != null && models.isArray()) {
            for (JsonNode node : models) {
                addModelEntry(result, node.path("id").asText(), null);
            }
        }
        return Result.ok(result);
    }

    /**
     * 防御性解析 /api/model/options 目录。
     * 实际结构（经实测确认）：顶层 providers 数组，每个 provider 的 models 字段
     * 为空格分隔的模型名字符串（如 "mimo-v2.5 mimo-v2.5-pro"），provider.slug 即渠道。
     * 兼容 models 为数组/对象的历史形态。
     */
    private List<Map<String, Object>> extractModelsFromCatalog(JsonNode catalog) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (catalog == null) return result;

        JsonNode providers = catalog.path("providers");
        if (!providers.isArray()) return result;

        for (JsonNode provider : providers) {
            String providerId = provider.path("slug").asText("");
            JsonNode modelsNode = provider.path("models");

            List<String> modelIds = new ArrayList<>();
            if (modelsNode.isTextual()) {
                for (String id : modelsNode.asText().split("\\s+")) {
                    if (!id.isBlank()) modelIds.add(id);
                }
            } else if (modelsNode.isArray()) {
                for (JsonNode m : modelsNode) {
                    if (m.isTextual()) {
                        modelIds.add(m.asText());
                    } else if (m.isObject()) {
                        modelIds.add(m.path("id").asText(m.path("model").asText(m.path("name").asText(""))));
                    }
                }
            } else if (modelsNode.isObject()) {
                modelsNode.fieldNames().forEachRemaining(modelIds::add);
            }

            for (String id : modelIds) {
                addModelEntry(result, id, providerId.isBlank() ? null : providerId);
            }
        }
        return result;
    }

    private void addModelEntry(List<Map<String, Object>> result, String id, String provider) {
        if (id == null || id.isBlank()) return;
        if ("hermes-agent".equalsIgnoreCase(id)) return; // 排除 Hermes 代理别名本身
        Map<String, Object> item = provider != null
                ? Map.of("id", id, "name", id, "provider", provider)
                : Map.of("id", id, "name", id);
        boolean exists = result.stream().anyMatch(m -> id.equals(m.get("id")));
        if (!exists) result.add(item);
    }

    @PostMapping("/sessions/{sessionId}/model")
    public Result<Void> lockModel(@RequestAttribute("userId") String userId,
                                  @PathVariable String sessionId,
                                  @RequestBody Map<String, Object> request) {
        if (!hermesProperties.isNativeApi()) {
            return Result.error("当前为兼容模式，不支持会话模型锁定");
        }
        UserAiConfig config = hermesService.getUserConfig(userId);
        String baseUrl = config != null && config.getBaseUrl() != null
                ? config.getBaseUrl() : hermesProperties.getBaseUrl();
        String apiKey = config != null && config.getApiKey() != null
                ? config.getApiKey() : hermesProperties.getApiKey();
        @SuppressWarnings("unchecked")
        Map<String, Object> modelOptions =
                request.get("modelOptions") instanceof Map ? (Map<String, Object>) request.get("modelOptions") : null;
        hermesNativeClient.lockModel(sessionId,
                (String) request.get("model"), (String) request.get("provider"),
                modelOptions, baseUrl, apiKey);
        return Result.ok();
    }

    // ─── 配置 ───

    @GetMapping("/config")
    public Result<UserAiConfig> getConfig(@RequestAttribute("userId") String userId) {
        UserAiConfig config = hermesService.getUserConfig(userId);
        return Result.ok(config);
    }

    @PutMapping("/config")
    public Result<UserAiConfig> updateConfig(@RequestAttribute("userId") String userId,
                                             @RequestBody UserAiConfig config) {
        // 保存前做出站地址校验（SSRF 防护，R2-01）：私有/环回网段一律拒绝
        if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
            ssrfGuard.validateOutbound(config.getBaseUrl());
        }
        UserAiConfig saved = hermesService.saveUserConfig(userId, config);
        return Result.ok(saved);
    }
}
