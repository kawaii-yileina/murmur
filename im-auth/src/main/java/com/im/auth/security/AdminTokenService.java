package com.im.auth.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/** 独立的管理端 JWT，不与普通用户 token 混用。 */
@Component
public class AdminTokenService {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${im.admin.token-expiration:28800000}")
    private long expirationMs;

    public String generate(Long adminId, int role) {
        Date now = new Date();
        return Jwts.builder()
                .subject("admin:" + adminId)
                .claim("adm", true)
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key())
                .compact();
    }

    public AdminPrincipal parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key()).build()
                    .parseSignedClaims(token).getPayload();
            if (!Boolean.TRUE.equals(claims.get("adm", Boolean.class))) {
                return null;
            }
            String subject = claims.getSubject();
            if (subject == null || !subject.startsWith("admin:")) {
                return null;
            }
            Number role = claims.get("role", Number.class);
            return new AdminPrincipal(
                    Long.parseLong(subject.substring("admin:".length())),
                    role == null ? 1 : role.intValue());
        } catch (Exception e) {
            return null;
        }
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public record AdminPrincipal(Long id, int role) {
        public boolean isSuper() {
            return role == 0;
        }
    }
}
