package com.im.core.admin.service;

import com.im.core.chat.router.RealtimeMessageRouter;
import com.elaina.chat.core.message.ChatMessage;
import com.im.common.constant.ChatConstants;
import com.im.common.enums.SystemEvent;
import com.im.core.auth.service.TokenVersionService;
import com.im.common.exception.BizException;
import com.im.core.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserModerationService {

    private static final Set<Long> ALLOWED_DURATION_SECONDS = Set.of(
            3600L, 86400L, 604800L, 2592000L);

    private final UserService userService;
    private final TokenVersionService tokenVersionService;
    private final RealtimeMessageRouter realtimeMessageRouter;

    public void ban(Long userId, String reason, Long adminId, Long durationSeconds) {
        LocalDateTime expireAt = null;
        if (durationSeconds != null && durationSeconds > 0) {
            if (!ALLOWED_DURATION_SECONDS.contains(durationSeconds)) {
                throw new BizException(400, "封禁时长不合法");
            }
            expireAt = LocalDateTime.now().plusSeconds(durationSeconds);
        }
        userService.setBanned(userId, true, reason, adminId, expireAt);
        tokenVersionService.bump(String.valueOf(userId));
        kick(userId, SystemEvent.ACCOUNT_BANNED);
    }

    public void unban(Long userId) {
        userService.setBanned(userId, false, null, null);
        // 解封后旧 token 仍不可用，需要重新登录
        tokenVersionService.bump(String.valueOf(userId));
    }

    private void kick(Long userId, SystemEvent event) {
        try {
            ChatMessage message = ChatMessage.builder()
                    .type(ChatConstants.MSG_TYPE_SYSTEM)
                    .from(ChatConstants.SYSTEM_USER)
                    .to(String.valueOf(userId))
                    .group(false)
                    .content(event.format())
                    .build();
            realtimeMessageRouter.kickUser(String.valueOf(userId), message);
        } catch (Exception e) {
            log.warn("Failed to kick banned user {}: {}", userId, e.getMessage());
        }
    }
}
