package com.im.core.online.listener;

import com.elaina.chat.core.gateway.MessageGateway;
import com.elaina.chat.core.message.ChatMessage;
import com.im.core.online.service.OnlineStatusService;
import com.im.core.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisKeyExpirationListener implements MessageListener {

    private final RedisMessageListenerContainer listenerContainer;
    private final OnlineStatusService onlineStatusService;
    private final MessageGateway messageGateway;
    private final UserService userService;

    private static final String ONLINE_KEY_PREFIX = "im:online:";

    @PostConstruct
    public void init() {
        listenerContainer.addMessageListener(this, new ChannelTopic("__keyevent@0__:expired"));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = new String(message.getBody());
        if (expiredKey.startsWith(ONLINE_KEY_PREFIX)) {
            String userId = expiredKey.substring(ONLINE_KEY_PREFIX.length());
            log.info("Online status key expired for user: {}", userId);
            onlineStatusService.userOffline(userId);
            notifyFriends(userId);
        }
    }

    private void notifyFriends(String userId) {
        try {
            List<Long> friendIds = userService.getFriendIds(Long.parseLong(userId));
            ChatMessage statusMsg = ChatMessage.builder()
                    .type("SYSTEM")
                    .content("offline:" + userId)
                    .build();
            for (Long friendId : friendIds) {
                messageGateway.sendToUser(String.valueOf(friendId), statusMsg);
            }
        } catch (Exception e) {
            log.error("Failed to notify friends for expired user {}", userId, e);
        }
    }
}
