package com.im.core.message.service;

import com.elaina.chat.core.message.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

    public void storeOfflineMessage(String userId, ChatMessage message) {
        try {
            String key = OFFLINE_KEY_PREFIX + userId;
            String json = objectMapper.writeValueAsString(message);
            
            redisTemplate.opsForList().rightPush(key, json);
            redisTemplate.expire(key, OFFLINE_EXPIRE_HOURS, TimeUnit.HOURS);
            
            Long size = redisTemplate.opsForList().size(key);
            if (size != null && size > MAX_OFFLINE_MESSAGES) {
                redisTemplate.opsForList().leftPop(key);
            }
            
            log.debug("Stored offline message for user {}: {}", userId, message.getContent());
        } catch (Exception e) {
            log.error("Failed to store offline message for user {}", userId, e);
        }
    }

    public List<ChatMessage> getOfflineMessages(String userId) {
        try {
            String key = OFFLINE_KEY_PREFIX + userId;
            List<String> jsonList = redisTemplate.opsForList().range(key, 0, -1);
            
            if (jsonList == null || jsonList.isEmpty()) {
                return new ArrayList<>();
            }
            
            List<ChatMessage> messages = new ArrayList<>();
            for (String json : jsonList) {
                try {
                    messages.add(objectMapper.readValue(json, ChatMessage.class));
                } catch (Exception e) {
                    log.warn("Failed to parse offline message: {}", json);
                }
            }
            
            redisTemplate.delete(key);
            
            log.debug("Retrieved {} offline messages for user {}", messages.size(), userId);
            return messages;
        } catch (Exception e) {
            log.error("Failed to get offline messages for user {}", userId, e);
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
