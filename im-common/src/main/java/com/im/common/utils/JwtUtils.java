package com.im.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

public class JwtUtils {

    private JwtUtils() {}

    public static String generateToken(String userId, String secret, long expirationMs) {
        return generateToken(userId, secret, expirationMs, 0);
    }

    /**
     * 生成 access token，携带 token 版本号（ver）。
     * 改密/注销后版本递增，旧版本 token 即失效，实现全端即时登出。
     */
    public static String generateToken(String userId, String secret, long expirationMs, int version) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(userId)
                .claim("ver", version)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /** 读取 token 版本号；无 ver 声明（旧 token）返回 0；解析失败返回 null */
    public static Integer getVersion(String token, String secret) {
        try {
            Claims claims = parseToken(token, secret);
            Number version = claims.get("ver", Number.class);
            return version != null ? version.intValue() : 0;
        } catch (Exception e) {
            return null;
        }
    }

    public static Claims parseToken(String token, String secret) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public static String getUserIdFromToken(String token, String secret) {
        Claims claims = parseToken(token, secret);
        return claims.getSubject();
    }

    public static String getUserIdFromExpiredToken(String token, String secret) {
        try {
            return getUserIdFromToken(token, secret);
        } catch (Exception e) {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return null;
            String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            int start = payload.indexOf("\"sub\":\"") + 7;
            int end = payload.indexOf("\"", start);
            return start > 6 && end > start ? payload.substring(start, end) : null;
        }
    }

    public static boolean isTokenExpired(String token, String secret) {
        Claims claims = parseToken(token, secret);
        return claims.getExpiration().before(new Date());
    }
}
