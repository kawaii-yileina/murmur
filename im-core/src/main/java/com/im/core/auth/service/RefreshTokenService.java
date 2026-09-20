package com.im.core.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

/**
 * Refresh Token 服务：不透明随机串，Redis 存储（可撤销、可多端）。
 * 键约定：
 * - {@code im:refresh:{token}} → userId（TTL = refresh 有效期）
 * - {@code im:refresh:user:{userId}} → 该用户已签发的 token 集合（用于按用户批量撤销）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final StringRedisTemplate redisTemplate;

    private static final String TOKEN_KEY_PREFIX = "im:refresh:";
    private static final String USER_KEY_PREFIX = "im:refresh:user:";
    private static final String PREV_KEY_PREFIX = "im:refresh:prev:";
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Value("${jwt.refresh-expiration}")
    private long refreshExpirationMs;

    /** 轮换后旧 token 的宽限期（毫秒），用于容忍并发刷新与响应丢失 */
    @Value("${jwt.refresh-grace:60000}")
    private long refreshGraceMs;

    /** 签发新的 refresh token 并登记到 Redis */
    public String issue(String userId) {
        String token = generateToken();
        store(token, userId, Duration.ofMillis(refreshExpirationMs));
        return token;
    }

    /**
     * 轮换 refresh token：签发新 token 并登记，同时给旧 token 保留一段宽限期。
     * 宽限期内用同一个旧 token 重试（如多标签页并发刷新、或上次刷新响应丢失）会
     * 幂等返回首次轮换出的新 token，避免后到的请求拿到已失效 token 被强制登出。
     */
    public String rotate(String oldToken, String userId) {
        String existing = redisTemplate.opsForValue().get(PREV_KEY_PREFIX + oldToken);
        if (existing != null) {
            return existing;
        }

        String newToken = generateToken();
        store(newToken, userId, Duration.ofMillis(refreshExpirationMs));

        Duration grace = Duration.ofMillis(refreshGraceMs);
        redisTemplate.opsForValue().set(PREV_KEY_PREFIX + oldToken, newToken, grace);
        redisTemplate.expire(TOKEN_KEY_PREFIX + oldToken, grace);
        return newToken;
    }

    private void store(String token, String userId, Duration ttl) {
        redisTemplate.opsForValue().set(TOKEN_KEY_PREFIX + token, userId, ttl);
        String userKey = USER_KEY_PREFIX + userId;
        redisTemplate.opsForSet().add(userKey, token);
        redisTemplate.expire(userKey, ttl);
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 校验 refresh token，有效则返回 userId，否则返回 null */
    public String validateAndGetUserId(String token) {
        if (token == null || token.isBlank()) return null;
        return redisTemplate.opsForValue().get(TOKEN_KEY_PREFIX + token);
    }

    /** 撤销单个 refresh token（登出 / 按用户批量撤销时调用） */
    public void revoke(String token) {
        if (token == null || token.isBlank()) return;
        String key = TOKEN_KEY_PREFIX + token;
        String userId = redisTemplate.opsForValue().get(key);
        redisTemplate.delete(key);
        redisTemplate.delete(PREV_KEY_PREFIX + token);
        if (userId != null) {
            redisTemplate.opsForSet().remove(USER_KEY_PREFIX + userId, token);
        }
    }

    /**
     * 撤销某用户已签发的全部 refresh token（改密 / 找回密码 / 注销后调用）。
     * 撤销后所有端的刷新能力失效，需重新登录。
     */
    public void revokeAllForUser(String userId) {
        if (userId == null || userId.isBlank()) return;
        String userKey = USER_KEY_PREFIX + userId;
        Set<String> tokens = redisTemplate.opsForSet().members(userKey);
        if (tokens != null) {
            for (String token : tokens) {
                redisTemplate.delete(TOKEN_KEY_PREFIX + token);
                redisTemplate.delete(PREV_KEY_PREFIX + token);
            }
        }
        redisTemplate.delete(userKey);
    }
}
