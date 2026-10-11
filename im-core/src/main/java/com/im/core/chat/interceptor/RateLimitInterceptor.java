package com.im.core.chat.interceptor;

import com.elaina.chat.core.handler.HandlerContext;
import com.elaina.chat.core.handler.MessageInterceptor;
import com.elaina.chat.core.message.ChatMessage;
import com.im.common.constant.ChatConstants;
import com.im.common.enums.SystemEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 发送频率限流：用户级固定窗口计数（1 秒窗口），超限时阻断发送并回 SYSTEM 提示。
 * 只统计真实聊天消息；SYSTEM / HEARTBEAT / TYPING 等瞬时或系统消息不计入。
 * 位于持久化链路之外（仅依赖 Redis），不涉及 {@code MessageGateway}，无循环依赖风险。
 * 注意：本类刻意不声明 @Component。它实现 MessageInterceptor，若成为 Spring Bean，
 * Spring 注入 MessageDispatcher 的 {@code List<MessageInterceptor>} 时会收集它，
 * 从而顶掉框架 buildInterceptors() 中自带的持久化拦截器，导致消息不落库、不回 ACK。
 * 因此由 ChatConfiguration 手动构造并注册。
 */
@Slf4j
@Component
public class RateLimitInterceptor implements MessageInterceptor {

    private static final String KEY_PREFIX = "im:ratelimit:msg:";
    private static final Set<String> SKIP_TYPES = Set.of(
            ChatConstants.SYSTEM_USER, "HEARTBEAT", ChatConstants.MSG_TYPE_TYPING, ChatConstants.MSG_TYPE_CALL);

    private final StringRedisTemplate redisTemplate;
    @Value("${im.rate-limit.messages-per-second:5}")
    private int maxPerSecond;

    public RateLimitInterceptor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preSend(ChatMessage message, HandlerContext ctx, List<String> targets) {
        String type = message.getType();
        String from = message.getFrom();
        if (maxPerSecond <= 0 || from == null || from.isBlank() || SKIP_TYPES.contains(type)
                || ChatConstants.isCallSignal(type)) {
            return true;
        }

        try {
            String key = KEY_PREFIX + from;
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofSeconds(1));
            }
            if (count != null && count > maxPerSecond) {
                log.warn("Rate limited user {}: {} msgs within 1s", from, count);
                ChatMessage error = ChatMessage.builder()
                        .type(ChatConstants.SYSTEM_USER)
                        .from(ChatConstants.SYSTEM_USER)
                        .to(from)
                        .group(false)
                        .content(SystemEvent.RATE_LIMITED.format())
                        .build();
                String clientMsgId = header(message, "clientMsgId");
                if (!clientMsgId.isBlank()) {
                    error.addHeader("clientMsgId", clientMsgId);
                }
                ctx.send(error);
                return false;
            }
        } catch (Exception e) {
            // Redis 异常时放行，避免影响正常收发
            log.error("Rate limit check failed for user {}, message allowed", from, e);
        }
        return true;
    }

    @Override
    public boolean postSend(ChatMessage message, HandlerContext ctx, List<String> targets, List<String> successList) {
        return true;
    }

    private String header(ChatMessage msg, String key) {
        if (msg.getHeaders() == null) return "";
        Object value = msg.getHeaders().get(key);
        return value != null ? String.valueOf(value) : "";
    }
}
