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

    /**
     * 落离线队列。序列化或 Redis 写入失败时抛出，交由调用方决定重试（Outbox / 消费端重投），
     * 不得静默吞掉导致消息丢失。
     */
    public void storeOfflineMessage(String userId, ChatMessage message) {
        String key = OFFLINE_KEY_PREFIX + userId;
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
     * 一次性取出并清空该用户的离线消息。读取失败（如 Redis 不可用）时返回空列表，
     * 消息仍保留在 Redis，待恢复后再次补投；最终可由会话 seq 增量补拉兜底。
     */
    @SuppressWarnings("unchecked")
    public List<ChatMessage> getOfflineMessages(String userId) {
        try {
            String key = OFFLINE_KEY_PREFIX + userId;
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

    public int getOfflineCount(String userId) {
        try {
            String key = OFFLINE_KEY_PREFIX + userId;
            Long size = redisTemplate.opsForList().size(key);
            return size != null ? size.intValue() : 0;
        } catch (Exception e) {
            log.error("Failed to get offline count for user {}", userId);
            return 0;
        }
    }

    public void clearOfflineMessages(String userId) {
        try {
            String key = OFFLINE_KEY_PREFIX + userId;
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("Failed to clear offline messages for user {}", userId);
        }
    }
}
