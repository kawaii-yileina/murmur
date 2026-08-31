package com.im.auth.jwt;

import com.im.common.utils.JwtUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration}")
    private long jwtExpiration;

    public String generateToken(String userId) {
        return JwtUtils.generateToken(userId, jwtSecret, jwtExpiration);
    }

    public String getUserIdFromToken(String token) {
        return JwtUtils.getUserIdFromToken(token, jwtSecret);
    }

    public String getUserIdFromExpiredToken(String token) {
        return JwtUtils.getUserIdFromExpiredToken(token, jwtSecret);
    }

    public boolean validateToken(String token) {
        try {
            return !JwtUtils.isTokenExpired(token, jwtSecret);
        } catch (Exception e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }
}
