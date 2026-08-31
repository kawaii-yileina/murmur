package com.im.hermes.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.hermes.client.HermesClient;
import com.im.hermes.client.HermesNativeClient;
import com.im.hermes.client.HermesProperties;
import com.im.hermes.model.AiConversation;
import com.im.hermes.model.AiConversationMapper;
import com.im.hermes.model.HermesMessage;
import com.im.hermes.model.HermesRequest;
import com.im.hermes.model.HermesResponse;
import com.im.hermes.model.UserAiConfig;
import com.im.hermes.model.UserAiConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Hermes AI 服务层
 *
 * 双轨架构：
 *   MySQL  = 消息存储（前端展示 / 历史浏览），全量保留，不裁剪
 *   Hermes = AI 会话上下文（native 模式下由 Hermes 管理历史拼接、图片路由）
 *
 * native 模式（hermes.native-api=true）：
 *   发送时只发当前消息（多模态内容在消息体内），Hermes 自己管理上下文。
 *   回复的 assistant.delta / assistant.completed 事件流转发给前端，
 *   完成后将完整回复存 MySQL。
 *
 * 降级模式（hermes.native-api=false）：
 *   走 OpenAI 兼容 /v1/chat/completions，从 MySQL 拼全量历史（旧行为）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HermesService {

    private final HermesClient hermesClient;
    private final HermesNativeClient hermesNativeClient;
    private final HermesProperties hermesProperties;
    private final AiConversationMapper aiConversationMapper;
    private final UserAiConfigMapper userAiConfigMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String AI_SESSION_PREFIX = "ai_";

    // ─── 同步对话（降级模式使用；native 模式下前端走流式）───

    public String chat(String userId, String userMessage, String sessionId) {
        if (hermesProperties.isNativeApi()) {
            // native 模式下同步对话：直接阻塞等待完整回复
            StringBuilder full = new StringBuilder();
            chatStream(userId, userMessage, sessionId)
                    .doOnNext(json -> {
                        JsonNode node = readTree(json);
                        if (node != null && "assistant.completed".equals(node.path("event").asText())) {
                            full.setLength(0);
                            full.append(node.path("data").path("content").asText());
                        }
                    })
                    .blockLast();
            if (full.length() > 0) return full.toString();
            throw new IllegalStateException("AI 未返回内容");
        }
        return chatViaCompatibleClient(userId, userMessage, sessionId);
    }

    // ─── 流式对话 ───

    public Flux<String> chatStream(String userId, String userMessage, String sessionId) {
        if (hermesProperties.isNativeApi()) {
            return chatStreamNative(userId, userMessage, sessionId);
        }
        return streamViaCompatibleClient(userId, userMessage, sessionId);
    }

    /**
     * native 模式流式对话：
     * 1. 用户消息存 MySQL（展示用）
     * 2. 新会话 best-effort 同步创建 Hermes 会话
     * 3. 只发当前消息给 Hermes（Hermes 自己管理历史上下文）
     * 4. assistant 事件转发前端，完成后完整回复存 MySQL
     */
    private Flux<String> chatStreamNative(String userId, String userMessage, String sessionId) {
        ConnectionConfig cfg = resolveConnection(userId);

        boolean newSession = isNewSession(userId, sessionId);
        saveMessage(userId, sessionId, "user", userMessage);
        if (newSession) {
            hermesNativeClient.createSession(sessionId, null, null, cfg.model(), cfg.baseUrl(), cfg.apiKey());
        }

        Object nativeMessage = buildNativeMessage(userMessage);

        StringBuilder accumulated = new StringBuilder();
        StringBuilder completed = new StringBuilder();

        return hermesNativeClient.chatStream(
                        sessionId, nativeMessage, cfg.baseUrl(), cfg.apiKey(), cfg.model(), null)
                .doOnNext(json -> {
                    JsonNode node = readTree(json);
                    if (node == null) return;
                    String event = node.path("event").asText();
                    switch (event) {
                        case "assistant.delta" -> {
                            String delta = node.path("data").path("delta").asText();
                            accumulated.append(delta);
                        }
                        case "assistant.completed" -> {
                            String content = node.path("data").path("content").asText();
                            if (!content.isBlank()) completed.append(content);
                        }
                        case "error" -> log.warn("Hermes stream error event for user {}: {}",
                                userId, node.path("data").toString());
                    }
                })
                .doOnComplete(() -> {
                    String content = !completed.isEmpty() ? completed.toString() : accumulated.toString();
                    if (!content.isBlank()) {
                        saveMessage(userId, sessionId, "assistant", content);
                    }
                })
                .doOnError(e -> log.error("Hermes native stream failed for user {}", userId, e));
    }

    /**
     * 降级模式流式对话（旧行为）：MySQL 拼全量历史 → /v1/chat/completions
     */
    private Flux<String> streamViaCompatibleClient(String userId, String userMessage, String sessionId) {
        List<HermesMessage> history = loadHistory(userId, sessionId);

        history.add(HermesMessage.builder().role("user").content(userMessage).build());
        HermesRequest request = buildRequest(userId, history);

        StringBuilder fullResponse = new StringBuilder();

        return hermesClient.chatStream(request)
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> {
                    String content = fullResponse.toString();
                    history.add(HermesMessage.builder().role("assistant").content(content).build());
                    saveMessage(userId, sessionId, "assistant", content);
                    trimHistory(history, sessionId, userId);
                });
    }

    private String chatViaCompatibleClient(String userId, String userMessage, String sessionId) {
        List<HermesMessage> history = loadHistory(userId, sessionId);
        history.add(HermesMessage.builder().role("user").content(userMessage).build());

        HermesRequest request = buildRequest(userId, history);
        HermesResponse response = hermesClient.chat(request);
        String assistantContent = response.getContent();

        saveMessage(userId, sessionId, "assistant", assistantContent);
        trimHistory(history, sessionId, userId);
        return assistantContent;
    }

    // ─── 会话管理 ───

    public List<String> getSessions(String userId) {
        List<AiConversation> conversations = aiConversationMapper.findByUserId(
            Long.parseLong(userId)
        );
        return conversations.stream()
            .map(AiConversation::getSessionId)
            .distinct()
            .sorted()
            .toList();
    }

    public void clearSession(String userId, String sessionId) {
        aiConversationMapper.delete(
            new LambdaQueryWrapper<AiConversation>()
                .eq(AiConversation::getUserId, Long.parseLong(userId))
                .eq(AiConversation::getSessionId, sessionId)
        );
        if (hermesProperties.isNativeApi()) {
            ConnectionConfig cfg = resolveConnection(userId);
            hermesNativeClient.deleteSession(sessionId, cfg.baseUrl(), cfg.apiKey());
        }
        log.info("Cleared AI session {} for user {}", sessionId, userId);
    }

    /**
     * 截断会话消息：保留按 id 升序的前 keepCount 条，删除其后所有消息。
     * 用于编辑重发 / 重新生成 / 重新发送的"覆盖"语义（MySQL 展示数据同步）。
     */
    public void truncateMessagesFrom(String userId, String sessionId, int keepCount) {
        List<AiConversation> all = aiConversationMapper.selectList(
            new LambdaQueryWrapper<AiConversation>()
                .eq(AiConversation::getUserId, Long.parseLong(userId))
                .eq(AiConversation::getSessionId, sessionId)
                .orderByAsc(AiConversation::getId)
        );
        if (all.size() <= keepCount) return;
        List<Long> idsToDelete = all.subList(keepCount, all.size()).stream()
                .map(AiConversation::getId)
                .toList();
        aiConversationMapper.deleteBatchIds(idsToDelete);
        log.info("Truncated session {} for user {}: kept {}, removed {}",
                sessionId, userId, keepCount, idsToDelete.size());
    }

    // ─── 配置 ───

    public UserAiConfig getUserConfig(String userId) {
        return userAiConfigMapper.selectOne(
            new LambdaQueryWrapper<UserAiConfig>()
                .eq(UserAiConfig::getUserId, Long.parseLong(userId))
        );
    }

    public UserAiConfig saveUserConfig(String userId, UserAiConfig config) {
        config.setUserId(Long.parseLong(userId));
        UserAiConfig existing = getUserConfig(userId);
        if (existing != null) {
            config.setId(existing.getId());
            config.setCreatedAt(existing.getCreatedAt());
            userAiConfigMapper.updateById(config);
        } else {
            userAiConfigMapper.insert(config);
        }
        return config;
    }

    // ─── 内部工具 ───

    /** 用户连接配置：用户级配置优先，全局配置兜底 */
    private ConnectionConfig resolveConnection(String userId) {
        UserAiConfig config = getUserConfig(userId);
        String baseUrl = config != null && config.getBaseUrl() != null
                ? config.getBaseUrl() : hermesProperties.getBaseUrl();
        String apiKey = config != null && config.getApiKey() != null
                ? config.getApiKey() : hermesProperties.getApiKey();
        String model = config != null && config.getModel() != null
                ? config.getModel() : hermesProperties.getModel();
        return new ConnectionConfig(baseUrl, apiKey, model);
    }

    private record ConnectionConfig(String baseUrl, String apiKey, String model) {}

    /**
     * 构造 Hermes 原生消息体：
     * 纯文本 → "文本"
     * 多模态（前端传 JSON 数组字符串）→ {"role":"user","content":[...]}
     */
    private Object buildNativeMessage(String userMessage) {
        String trimmed = userMessage == null ? "" : userMessage.trim();
        if (trimmed.startsWith("[")) {
            try {
                JsonNode arr = objectMapper.readTree(trimmed);
                if (arr.isArray()) {
                    // Hermes 期望 message 是字符串或数组，不要包 role
                    return arr;
                }
            } catch (Exception e) {
                log.debug("Message not a multimodal array, treating as text");
            }
        }
        return userMessage;
    }

    private boolean isNewSession(String userId, String sessionId) {
        Long count = aiConversationMapper.selectCount(
            new LambdaQueryWrapper<AiConversation>()
                .eq(AiConversation::getUserId, Long.parseLong(userId))
                .eq(AiConversation::getSessionId, sessionId)
        );
        return count == null || count == 0;
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** 保存消息到 MySQL（展示用）。失败仅记日志，不影响对话主流程。 */
    private void saveMessage(String userId, String sessionId, String role, String content) {
        try {
            AiConversation conversation = new AiConversation();
            conversation.setUserId(Long.parseLong(userId));
            conversation.setSessionId(sessionId);
            conversation.setRole(role);
            conversation.setContent(content);
            conversation.setCreatedAt(LocalDateTime.now());
            aiConversationMapper.insert(conversation);
        } catch (Exception e) {
            log.error("Failed to save AI conversation for user {}", userId, e);
        }
    }

    // ─── 降级模式专用（native 模式不使用）───

    private List<HermesMessage> loadHistory(String userId, String sessionId) {
        List<AiConversation> conversations = aiConversationMapper.findByUserIdAndSessionId(
            Long.parseLong(userId), sessionId
        );

        List<HermesMessage> history = new ArrayList<>();
        for (AiConversation conv : conversations) {
            history.add(HermesMessage.builder()
                    .role(conv.getRole())
                    .content(conv.getContent())
                    .build());
        }

        trimHistory(history, sessionId, userId);
        return history;
    }

    private HermesRequest buildRequest(String userId, List<HermesMessage> history) {
        ConnectionConfig cfg = resolveConnection(userId);
        UserAiConfig config = getUserConfig(userId);

        Integer maxTokens = config != null && config.getMaxTokens() != null
                ? config.getMaxTokens() : hermesProperties.getMaxTokens();
        Double temperature = config != null && config.getTemperature() != null
                ? config.getTemperature() : hermesProperties.getTemperature();

        return HermesRequest.builder()
                .model(cfg.model())
                .messages(new ArrayList<>(history))
                .maxTokens(maxTokens)
                .temperature(temperature)
                .baseUrl(cfg.baseUrl())
                .apiKey(cfg.apiKey())
                .build();
    }

    private void trimHistory(List<HermesMessage> history, String sessionId, String userId) {
        final int MAX_HISTORY = 20;
        if (history.size() > MAX_HISTORY) {
            int toRemove = history.size() - MAX_HISTORY;
            List<AiConversation> allConversations = aiConversationMapper.findByUserIdAndSessionId(
                Long.parseLong(userId), sessionId
            );
            for (int i = 0; i < toRemove && i < allConversations.size(); i++) {
                aiConversationMapper.deleteById(allConversations.get(i).getId());
            }
            history.subList(0, toRemove).clear();
        }
    }
}
