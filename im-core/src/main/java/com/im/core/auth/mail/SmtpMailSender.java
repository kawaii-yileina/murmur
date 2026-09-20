package com.im.core.auth.mail;

import com.im.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 基于 Spring Mail（SMTP）的邮件发送实现。
 * 配置项：spring.mail.*（host/port/username/password）与环境变量；im.mail.enabled 控制开关。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmtpMailSender implements MailSender {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${im.mail.enabled:false}")
    private boolean enabled;

    @Value("${spring.mail.username:}")
    private String from;

    @Override
    public boolean isConfigured() {
        return enabled
                && from != null && !from.isBlank()
                && mailSenderProvider.getIfAvailable() != null;
    }

    @Override
    public void sendVerificationCode(String to, String code, int expireMinutes) {
        if (!isConfigured()) {
            throw new BizException(500, "邮件服务未配置，请联系管理员");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("IM 验证码");
        message.setText("您的验证码是：" + code + "，" + expireMinutes + " 分钟内有效。请勿泄露给他人。");
        try {
            mailSenderProvider.getObject().send(message);
        } catch (Exception e) {
            log.error("Failed to send verification email to {}", maskEmail(to), e);
            throw new BizException(500, "验证码发送失败，请稍后重试");
        }
        // 仅记录脱敏邮箱，不输出验证码
        log.info("Verification email sent to {}", maskEmail(to));
    }

    private String maskEmail(String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        if (at <= 1) return "***" + (at >= 0 ? email.substring(at) : "");
        return email.charAt(0) + "***" + email.substring(at);
    }
}
