package com.im.core.auth.mail;

/**
 * 邮件发送抽象，便于后续替换实现（SMTP / 第三方邮件服务）。
 */
public interface MailSender {

    /** 邮件服务是否已配置可用 */
    boolean isConfigured();

    /**
     * 发送验证码邮件。
     *
     * @param to            收件邮箱
     * @param code          验证码
     * @param expireMinutes 有效期（分钟）
     */
    void sendVerificationCode(String to, String code, int expireMinutes);
}
