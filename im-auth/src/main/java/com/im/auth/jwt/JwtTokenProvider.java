package com.im.auth.jwt;

import com.im.common.utils.JwtUtils;
import com.im.core.auth.service.TokenVersionService;
import io.jsonwebtoken.Claims;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final TokenVersionService tokenVersionService;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration}")
    private long jwtExpiration;

    /** HS256 要求密钥至少 256 bit（32 字节） */
    private static final int MIN_SECRET_BYTES = 32;

    @PostConstruct
    void validateSecret() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                "JWT secret 未配置：请通过环境变量 JWT_SECRET 注入（不要写进配置文件）");
        }
        if (jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                "JWT secret 长度不足 " + MIN_SECRET_BYTES + " 字节，无法满足 HS256 签名要求");
        }
    }

    public String generateToken(String userId) {
        return JwtUtils.generateToken(userId, jwtSecret, jwtExpiration, tokenVersionService.get(userId));
    }

    public String getUserIdFromToken(String token) {
        return JwtUtils.getUserIdFromToken(token, jwtSecret);
    }

    public String getUserIdFromExpiredToken(String token) {
        return JwtUtils.getUserIdFromExpiredToken(token, jwtSecret);
    }

    /**
     * 校验 token：签名/有效期有效，且 token 版本与当前用户版本一致。
     * 版本不一致说明用户已改密/注销，旧 token 立即失效。
     */
    public boolean validateToken(String token) {
        try {
            Claims claims = JwtUtils.parseToken(token, jwtSecret);
            if (claims.getExpiration().before(new Date())) {
                return false;
            }
            Integer version = JwtUtils.getVersion(token, jwtSecret);
            return version != null && version == tokenVersionService.get(claims.getSubject());
        } catch (Exception e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }
}
