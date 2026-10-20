package com.im.core.chat.authenticator;

import com.im.common.constant.ChatConstants;
import com.im.core.auth.service.WsTicketService;
import com.im.core.user.entity.User;
import com.im.core.user.service.UserService;
import com.elaina.chat.core.connection.Connection;
import com.elaina.chat.core.auth.SessionAuthenticator;
import com.elaina.chat.core.exception.AuthenticationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * WebSocket 握手认证：只接受一次性短时 ticket（由已登录 REST 接口签发），
 * 不再接受 URL 中的 access token，避免 JWT 进入访问日志/浏览器历史。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsTicketAuthenticator implements SessionAuthenticator {

    private final WsTicketService wsTicketService;
    private final UserService userService;

    @Override
    public String authenticate(Connection connection, Map<String, Object> attributes) throws AuthenticationException {
        String ticket = (String) attributes.get("ticket");
        if (ticket == null || ticket.isBlank()) {
            throw new AuthenticationException("Missing authentication ticket");
        }

        String userId = wsTicketService.consume(ticket);
        if (userId == null) {
            throw new AuthenticationException("Invalid or expired authentication ticket");
        }
        User user;
        try {
            user = userService.findById(Long.parseLong(userId));
        } catch (NumberFormatException e) {
            throw new AuthenticationException("Invalid authentication ticket");
        }
        if (user == null || (user.getDeleted() != null && user.getDeleted() == 1)
                || userService.isBanned(user)) {
            throw new AuthenticationException("Account unavailable");
        }
        attributes.put(ChatConstants.ATTR_AUTHED_USER_ID, userId);
        log.info("User authenticated via WS ticket: {}", userId);
        return userId;
    }
}
