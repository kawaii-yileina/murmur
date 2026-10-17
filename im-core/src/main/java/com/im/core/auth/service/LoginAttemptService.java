package com.im.core.auth.service;

import com.im.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 登录失败锁定（V-06）：同一用户名连续失败达到上限后锁定一段时间，防止口令爆破。
 * 键约定：
 * - im:login:fail:{username}  连续失败计数（TTL = 锁定时长，成功登录后清除）
 * - im:login:lock:{username}  锁定标记（存在即拒绝登录）
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private static final int MAX_FAILS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private static final String FAIL_PREFIX = "im:login:fail:";
    private static final String LOCK_PREFIX = "im:login:lock:";

    private final StringRedisTemplate redisTemplate;

    /** 登录前检查：锁定中直接拒绝，不暴露剩余次数 */
    public void checkLocked(String username) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(LOCK_PREFIX + username))) {
            throw new BizException(429, "失败次数过多，请稍后再试");
        }
    }

    /** 登录失败：累计失败次数，达到上限则锁定 */
    public void recordFailure(String username) {
        String failKey = FAIL_PREFIX + username;
        Long fails = redisTemplate.opsForValue().increment(failKey);
        if (fails != null && fails == 1L) {
            redisTemplate.expire(failKey, LOCK_DURATION);
        }
        if (fails != null && fails >= MAX_FAILS) {
            redisTemplate.opsForValue().set(LOCK_PREFIX + username, "1", LOCK_DURATION);
            redisTemplate.delete(failKey);
        }
    }

    /** 登录成功：清除失败计数 */
    public void recordSuccess(String username) {
        redisTemplate.delete(FAIL_PREFIX + username);
    }
}
