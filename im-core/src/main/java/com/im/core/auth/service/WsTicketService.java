package com.im.core.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * WebSocket 一次性短时票据：避免把 access token 放进 WS URL。
 * Redis TTL 30 秒，消费即删除；票据泄露最多被抢连一次，且很快失效。
 */
@Service
@RequiredArgsConstructor
public class WsTicketService {

    private static final String KEY_PREFIX = "im:ws:ticket:";
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;

    public IssuedTicket issue(String userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redisTemplate.opsForValue().set(KEY_PREFIX + ticket, userId, TTL);
        return new IssuedTicket(ticket, TTL.toSeconds());
    }

    /** 原子读取并删除；无效/过期/已使用返回 null */
    public String consume(String ticket) {
        if (ticket == null || ticket.isBlank() || ticket.length() > 128) {
            return null;
        }
        return redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + ticket);
    }

    public record IssuedTicket(String ticket, long expiresIn) {
    }
}
