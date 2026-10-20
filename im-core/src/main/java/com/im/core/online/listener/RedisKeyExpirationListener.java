package com.im.core.online.listener;

import com.elaina.chat.core.message.ChatMessage;
import com.im.common.constant.ChatConstants;
import com.im.core.chat.router.RealtimeMessageRouter;
import com.im.core.online.service.OnlineStatusService;
import com.im.core.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisKeyExpirationListener implements MessageListener {

    private final RedisMessageListenerContainer listenerContainer;
    private final OnlineStatusService onlineStatusService;
    private final RealtimeMessageRouter realtimeMessageRouter;
    private final UserService userService;
    private final StringRedisTemplate redisTemplate;

    private static final String ONLINE_KEY_PREFIX = "im:online:";
    private static final String OFFLINE_NOTIFY_LOCK_PREFIX = "im:presence:expire:notify:";

    @PostConstruct
    public void init() {
        listenerContainer.addMessageListener(this, new ChannelTopic("__keyevent@0__:expired"));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = new String(message.getBody());
        if (!expiredKey.startsWith(ONLINE_KEY_PREFIX)) {
            return;
        }
        String userId = expiredKey.substring(ONLINE_KEY_PREFIX.length());
        // Redis 过期事件会广播到所有节点，抢到短锁的节点负责状态切换与好友通知
        Boolean leader = redisTemplate.opsForValue().setIfAbsent(
                OFFLINE_NOTIFY_LOCK_PREFIX + userId, "1", Duration.ofSeconds(10));
        if (!Boolean.TRUE.equals(leader)) {
            return;
        }
        log.info("Online status key expired for user: {}", userId);
        onlineStatusService.userOffline(userId);
        notifyFriends(userId);
    }

    private void notifyFriends(String userId) {
        try {
            List<Long> friendIds = userService.getFriendIds(Long.parseLong(userId));
            ChatMessage statusMsg = ChatMessage.builder()
                    .type(ChatConstants.MSG_TYPE_SYSTEM)
                    .from(ChatConstants.SYSTEM_USER)
                    .content("offline:" + userId)
                    .build();
            for (Long friendId : friendIds) {
                realtimeMessageRouter.sendToUser(String.valueOf(friendId), statusMsg);
            }
        } catch (Exception e) {
            log.error("Failed to notify friends for expired user {}", userId, e);
        }
    }
}
