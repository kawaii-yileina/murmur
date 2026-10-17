package com.im.core.chat.authenticator;

import com.im.common.utils.JwtUtils;
import com.im.common.constant.ChatConstants;
import com.im.core.auth.service.TokenVersionService;
import com.elaina.chat.core.connection.Connection;
import com.elaina.chat.core.auth.SessionAuthenticator;
import com.elaina.chat.core.exception.AuthenticationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticator implements SessionAuthenticator {

    private final TokenVersionService tokenVersionService;

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
            Integer version = JwtUtils.getVersion(token, jwtSecret);
            if (version == null || version != tokenVersionService.get(userId)) {
                throw new AuthenticationException("Token 已失效，请重新登录");
            }
            // 记录认证身份到连接属性：供入站强制 from 归属（防冒充他人）
            attributes.put(ChatConstants.ATTR_AUTHED_USER_ID, userId);
            log.info("User authenticated via JWT: {}", userId);
            return userId;
        } catch (AuthenticationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Invalid JWT token");
            throw new AuthenticationException("Invalid authentication token");
        }
    }
}
