package com.im.core.chat.authenticator;

import com.im.common.utils.JwtUtils;
import com.elaina.chat.core.connection.Connection;
import com.elaina.chat.core.auth.SessionAuthenticator;
import com.elaina.chat.core.exception.AuthenticationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class JwtAuthenticator implements SessionAuthenticator {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Override
    public String authenticate(Connection connection, Map<String, Object> attributes) throws AuthenticationException {
        String token = (String) attributes.get("token");
        if (token == null || token.isBlank()) {
            throw new AuthenticationException("Missing authentication token");
        }

        try {
            String userId = JwtUtils.getUserIdFromToken(token, jwtSecret);
            log.info("User authenticated via JWT: {}", userId);
            return userId;
        } catch (Exception e) {
            log.warn("Invalid JWT token");
            throw new AuthenticationException("Invalid authentication token");
        }
    }
}
