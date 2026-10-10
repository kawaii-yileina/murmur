package com.im.core.message.service;

import com.elaina.chat.core.message.ChatMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 离线消息队列（Redis，加速用）。事实来源是 MySQL 历史消息 + 会话 seq 增量补拉。
 *
 * Key：
 * - 指定设备：im:offline:{userId}:{deviceId}
 * - 用户级（无设备信息时，如派发时目标无路由）：im:offline:{userId}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfflineMessageService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String OFFLINE_KEY_PREFIX = "im:offline:";
    private static final int MAX_OFFLINE_MESSAGES = 500;
    private static final int OFFLINE_EXPIRE_HOURS = 72;

    /** 原子取出全部离线消息并删除 key（避免 read 与 delete 之间新入队消息被连带丢弃） */
    private static final RedisScript<List> DRAIN_SCRIPT = RedisScript.of(
            "local v = redis.call('LRANGE', KEYS[1], 0, -1) " +
            "redis.call('DEL', KEYS[1]) " +
            "return v", List.class);

    /** 用户级入队（无设备信息时使用） */
    public void storeOfflineMessage(String userId, ChatMessage message) {
        store(userKey(userId), userId, message);
    }

    /** 指定设备入队 */
    public void storeOfflineMessage(String userId, String deviceId, ChatMessage message) {
        store(deviceKey(userId, deviceId), userId, message);
    }

    /** 用户级取出并清空 */
    public List<ChatMessage> getOfflineMessages(String userId) {
        return drain(userKey(userId), userId);
    }

    /** 指定设备取出并清空 */
    public List<ChatMessage> getOfflineMessages(String userId, String deviceId) {
        return drain(deviceKey(userId, deviceId), userId);
    }

    /**
     * 落离线队列。序列化或 Redis 写入失败时抛出，交由调用方决定重试（Outbox / 消费端重投），
     * 不得静默吞掉导致消息丢失。
     */
    private void store(String key, String userId, ChatMessage message) {
        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Serialize offline message failed for user " + userId, e);
        }
        redisTemplate.opsForList().rightPush(key, json);
        redisTemplate.expire(key, OFFLINE_EXPIRE_HOURS, TimeUnit.HOURS);

        Long size = redisTemplate.opsForList().size(key);
        if (size != null && size > MAX_OFFLINE_MESSAGES) {
            redisTemplate.opsForList().leftPop(key);
        }
        log.debug("Stored offline message for user {}, msgId={}", userId, message.getId());
    }

    /**
     * 一次性取出并清空该 key 的离线消息。读取失败（如 Redis 不可用）返回空列表，
     * 消息仍保留在 Redis，待恢复后再次补投；最终可由会话 seq 增量补拉兜底。
     */
    @SuppressWarnings("unchecked")
    private List<ChatMessage> drain(String key, String userId) {
        try {
            List<String> jsonList = redisTemplate.execute(DRAIN_SCRIPT, Collections.singletonList(key));
            if (jsonList == null || jsonList.isEmpty()) {
                return new ArrayList<>();
            }
            List<ChatMessage> messages = new ArrayList<>();
            for (String json : jsonList) {
                try {
                    messages.add(objectMapper.readValue(json, ChatMessage.class));
                } catch (Exception e) {
                    log.warn("Failed to parse offline message for user {}", userId);
                }
            }
            log.debug("Retrieved {} offline messages for user {}", messages.size(), userId);
            return messages;
        } catch (Exception e) {
            log.error("Failed to get offline messages for user {}: {}", userId, e.getMessage());
            return new ArrayList<>();
        }
    }

    private String userKey(String userId) {
        return OFFLINE_KEY_PREFIX + userId;
    }

    private String deviceKey(String userId, String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return userKey(userId);
        }
        return OFFLINE_KEY_PREFIX + userId + ":" + deviceId;
    }
}
