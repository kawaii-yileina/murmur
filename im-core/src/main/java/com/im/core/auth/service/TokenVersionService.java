package com.im.core.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Token 版本服务：为每个用户维护一个版本号，access token 携带签发时的版本。
 * 改密 / 找回密码 / 注销后递增版本，旧版本 access token 立即失效，实现全端即时登出。
 *
 * 键约定：im:token:ver:{userId} → 版本号（不存在视为 0）
 */
@Service
@RequiredArgsConstructor
public class TokenVersionService {

    private final StringRedisTemplate redisTemplate;

    private static final String KEY_PREFIX = "im:token:ver:";

    /** 读取当前版本号；不存在返回 0 */
    public int get(String userId) {
        if (userId == null || userId.isBlank()) return 0;
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
        if (value == null) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 递增版本号（不设 TTL，长期有效） */
    public void bump(String userId) {
        if (userId == null || userId.isBlank()) return;
        redisTemplate.opsForValue().increment(KEY_PREFIX + userId);
    }
}
