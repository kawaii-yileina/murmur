package com.im.core.cluster;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 消息投递幂等标记：以 (msgId, userId, deviceId) 为键，供本地直投、Outbox 派发、跨节点消费共用，
 * 保证同一用户的同一设备对同一消息"至少一次投递、至多一次落地"。
 *
 * 键必须含 userId：群消息对多个用户投递，共享同一 msgId；若仅用 (msgId, deviceId)，
 * 多个用户的默认设备标识相同会互相撞键，导致部分用户被误判"已处理"而收不到消息。
 *
 * 写入时机在投递成功之后（避免"先标记后写失败"导致丢消息）；失败重投可能重复，属至少一次边界，
 * 由客户端按 msgId 去重收敛。
 */
@Component
@RequiredArgsConstructor
public class MessageDedupService {

    private static final String DEDUP_PREFIX = "im:msg:dedup:";
    private static final Duration DEDUP_TTL = Duration.ofHours(24);
    private static final String DEFAULT_DEVICE = "default";

    private final StringRedisTemplate redis;

    public boolean isProcessed(String msgId, String userId, String deviceId) {
        if (msgId == null || msgId.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redis.hasKey(key(msgId, userId, deviceId)));
    }

    public void markProcessed(String msgId, String userId, String deviceId) {
        if (msgId == null || msgId.isBlank()) {
            return;
        }
        redis.opsForValue().set(key(msgId, userId, deviceId), "1", DEDUP_TTL);
    }

    private String key(String msgId, String userId, String deviceId) {
        String device = deviceId != null && !deviceId.isBlank() ? deviceId : DEFAULT_DEVICE;
        return DEDUP_PREFIX + msgId + ":" + userId + ":" + device;
    }
}
