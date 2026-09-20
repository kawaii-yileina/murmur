package com.im.core.auth.service;

import com.im.common.enums.VerifyScene;
import com.im.common.exception.BizException;
import com.im.core.auth.mail.MailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 邮箱验证码服务：生成、发送、校验，并内置防爆破策略。
 *
 * 键约定：
 * - im:verify:code:{scene}:{email}     验证码（TTL 5 分钟）
 * - im:verify:attempt:{scene}:{email}  校验失败次数（超过上限作废验证码）
 * - im:verify:cooldown:{scene}:{email} 发送冷却（60 秒）
 * - im:verify:hour:{scene}:{email}     每小时发送上限
 * - im:verify:day:{scene}:{email}      每日发送上限
 * - im:verify:ip:{ip}                  IP 每小时发送上限（防批量刷）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationCodeService {

    private final StringRedisTemplate redisTemplate;
    private final MailSender mailSender;

    private static final int CODE_TTL_MINUTES = 5;
    private static final int RESEND_COOLDOWN_SECONDS = 60;
    private static final int MAX_PER_HOUR = 5;
    private static final int MAX_PER_DAY = 15;
    private static final int MAX_PER_IP_PER_HOUR = 20;
    private static final int MAX_VERIFY_ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private static final String CODE_PREFIX = "im:verify:code:";
    private static final String ATTEMPT_PREFIX = "im:verify:attempt:";
    private static final String COOLDOWN_PREFIX = "im:verify:cooldown:";
    private static final String HOUR_PREFIX = "im:verify:hour:";
    private static final String DAY_PREFIX = "im:verify:day:";
    private static final String IP_PREFIX = "im:verify:ip:";

    /**
     * 生成并发送验证码。任一频控命中即拒绝，防止接口被爆破。
     */
    public void send(VerifyScene scene, String email, String clientIp) {
        if (scene == null) {
            throw new BizException(400, "验证码场景不合法");
        }
        String target = normalizeEmail(email);

        // 发送冷却：同一邮箱同一场景 60 秒内只能发一次
        String cooldownKey = COOLDOWN_PREFIX + scene.getScene() + ":" + target;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(cooldownKey, "1", Duration.ofSeconds(RESEND_COOLDOWN_SECONDS));
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BizException(429, "发送过于频繁，请稍后再试");
        }

        // 邮箱维度的小时/日上限
        if (incrWithTtl(HOUR_PREFIX + scene.getScene() + ":" + target, Duration.ofHours(1)) > MAX_PER_HOUR
                || incrWithTtl(DAY_PREFIX + scene.getScene() + ":" + target, Duration.ofHours(24)) > MAX_PER_DAY) {
            throw new BizException(429, "发送次数已达上限，请稍后再试");
        }
        // IP 维度上限，防止换邮箱批量刷
        if (clientIp != null && !clientIp.isBlank()
                && incrWithTtl(IP_PREFIX + clientIp, Duration.ofHours(1)) > MAX_PER_IP_PER_HOUR) {
            throw new BizException(429, "操作过于频繁，请稍后再试");
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String codeKey = CODE_PREFIX + scene.getScene() + ":" + target;
        redisTemplate.opsForValue().set(codeKey, code, Duration.ofMinutes(CODE_TTL_MINUTES));
        redisTemplate.delete(ATTEMPT_PREFIX + scene.getScene() + ":" + target);

        try {
            mailSender.sendVerificationCode(target, code, CODE_TTL_MINUTES);
        } catch (RuntimeException e) {
            // 发送失败时回收验证码与冷却，允许用户立即重试
            redisTemplate.delete(codeKey);
            redisTemplate.delete(cooldownKey);
            throw e;
        }
        log.info("Verification code issued, scene={}, email={}", scene.getScene(), maskEmail(target));
    }

    /**
     * 校验验证码；错误次数超限则作废验证码，需重新获取。
     */
    public void verify(VerifyScene scene, String email, String code) {
        if (scene == null) {
            throw new BizException(400, "验证码场景不合法");
        }
        String target = normalizeEmail(email);
        if (code == null || code.isBlank()) {
            throw new BizException(400, "验证码不能为空");
        }

        String codeKey = CODE_PREFIX + scene.getScene() + ":" + target;
        String stored = redisTemplate.opsForValue().get(codeKey);
        if (stored == null) {
            throw new BizException(400, "验证码已过期或未发送");
        }

        long attempts = incrWithTtl(ATTEMPT_PREFIX + scene.getScene() + ":" + target,
                Duration.ofMinutes(CODE_TTL_MINUTES));
        if (attempts > MAX_VERIFY_ATTEMPTS) {
            redisTemplate.delete(codeKey);
            throw new BizException(429, "尝试次数过多，请重新获取验证码");
        }
        if (!stored.equals(code.trim())) {
            throw new BizException(400, "验证码错误");
        }

        redisTemplate.delete(codeKey);
        redisTemplate.delete(ATTEMPT_PREFIX + scene.getScene() + ":" + target);
    }

    private String normalizeEmail(String email) {
        if (email == null || !EMAIL_PATTERN.matcher(email.trim()).matches()) {
            throw new BizException(400, "邮箱格式不正确");
        }
        return email.trim().toLowerCase();
    }

    private long incrWithTtl(String key, Duration ttl) {
        Long value = redisTemplate.opsForValue().increment(key);
        if (value != null && value == 1L) {
            redisTemplate.expire(key, ttl);
        }
        return value != null ? value : 0L;
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***" + (at >= 0 ? email.substring(at) : "");
        return email.charAt(0) + "***" + email.substring(at);
    }
}
