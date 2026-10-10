package com.im.core.chat.listener;

import com.elaina.chat.core.event.ChatEventListener;
import com.elaina.chat.core.connection.Connection;
import com.elaina.chat.core.message.ChatMessage;
import com.elaina.chat.core.gateway.MessageGateway;
import com.elaina.chat.core.registry.UserConnectionRegistry;
import com.im.common.utils.JwtUtils;
import com.im.core.cluster.ConnAttributes;
import com.im.core.cluster.config.ClusterProperties;
import com.im.core.cluster.route.RouteRegistry;
import com.im.core.message.service.OfflineMessageService;
import com.im.core.online.service.OnlineStatusService;
import com.im.core.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class StatusEventListener implements ChatEventListener {

    private final OnlineStatusService onlineStatusService;
    private final OfflineMessageService offlineMessageService;
    private final UserConnectionRegistry connectionRegistry;
    private final MessageGateway messageGateway;
    private final UserService userService;
    private final RouteRegistry routeRegistry;
    private final ClusterProperties clusterProperties;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.token-name}")
    private String tokenName;

    @Override
    public void onConnectionOpen(Connection connection) {
        log.debug("Connection opened: {}", connection.id());
    }

    @Override
    public void onAuthenticated(String userId, Connection connection) {
        log.info("User authenticated: {}", userId);
        onlineStatusService.userOnline(userId);
        registerRoute(userId, connection);
        notifyFriends(userId, "online:" + userId);
        sendOfflineMessages(userId);
    }

    @Override
    public void onConnectionClose(Connection connection) {
        try {
            String token = (String) connection.attributes().get(tokenName);
            String userId = JwtUtils.getUserIdFromToken(token, jwtSecret);
            if (userId != null) {
                log.info("User disconnected: {}", userId);
                unregisterRoute(userId, connection);
                onlineStatusService.userOffline(userId);
                notifyFriends(userId, "offline:" + userId);
            }
        } catch (Exception e) {
            log.warn("Failed to handle connection close: {}", e.getMessage());
        }
    }

    /** 集群模式下登记连接路由（未开启集群时为空操作，保持单机行为不变） */
    private void registerRoute(String userId, Connection connection) {
        if (!clusterProperties.isEnabled()) {
            return;
        }
        try {
            routeRegistry.register(userId, ConnAttributes.deviceId(connection), connection.id(),
                    ConnAttributes.platform(connection));
        } catch (Exception e) {
            log.error("Failed to register route for user {}", userId, e);
        }
    }

    /** 集群模式下移除连接路由：仅当路由仍属于本连接时才删（旧连接下线不会误删新路由） */
    private void unregisterRoute(String userId, Connection connection) {
        if (!clusterProperties.isEnabled()) {
            return;
        }
        try {
            routeRegistry.unregister(userId, ConnAttributes.deviceId(connection), connection.id());
        } catch (Exception e) {
            log.error("Failed to unregister route for user {}", userId, e);
        }
    }

    private void notifyFriends(String userId, String content) {
        try {
            List<Long> friendIds = userService.getFriendIds(Long.parseLong(userId));
            ChatMessage statusMsg = ChatMessage.builder()
                    .type("SYSTEM")
                    .content(content)
                    .build();
            for (Long friendId : friendIds) {
                messageGateway.sendToUser(String.valueOf(friendId), statusMsg);
            }
        } catch (Exception e) {
            log.error("Failed to notify friends for user {}", userId, e);
        }
    }

    private void sendOfflineMessages(String userId) {
        try {
            List<ChatMessage> offlineMessages = offlineMessageService.getOfflineMessages(userId);
            if (offlineMessages.isEmpty()) return;

            log.info("Sending {} offline messages to user {}", offlineMessages.size(), userId);
            Connection connection = connectionRegistry.getConnection(userId);
            if (connection != null && connection.isActive()) {
                for (ChatMessage message : offlineMessages) {
                    connection.write(message);
                }
            }
        } catch (Exception e) {
            log.error("Failed to send offline messages to user {}", userId, e);
        }
    }
}
