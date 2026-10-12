package com.im.core.chat.listener;

import com.elaina.chat.core.event.ChatEventListener;
import com.elaina.chat.core.connection.Connection;
import com.elaina.chat.core.message.ChatMessage;
import com.elaina.chat.core.gateway.MessageGateway;
import com.im.core.chat.registry.MultiConnectionRegistry;
import com.im.common.utils.JwtUtils;
import com.im.common.constant.ChatConstants;
import com.im.core.call.CallService;
import com.im.core.call.CallSignalRouter;
import com.im.core.call.entity.CallSession;
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
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class StatusEventListener implements ChatEventListener {

    private final OnlineStatusService onlineStatusService;
    private final OfflineMessageService offlineMessageService;
    private final MultiConnectionRegistry connectionRegistry;
    private final MessageGateway messageGateway;
    private final UserService userService;
    private final RouteRegistry routeRegistry;
    private final ClusterProperties clusterProperties;
    private final CallService callService;
    private final CallSignalRouter callSignalRouter;

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
        sendOfflineMessages(userId, connection);
    }

    @Override
    public void onConnectionClose(Connection connection) {
        // 连接感知移除不依赖 token 解析，先执行，保证多端注册表始终准确（access token 可能已过期）
        String userId = resolveUserId(connection);
        connectionRegistry.removeConnection(connection);
        if (userId == null) {
            return;
        }
        log.info("User disconnected: {}", userId);
        unregisterRoute(userId, connection);
        // 仅当该用户再无任何在线设备（本节点或其他节点）时，才置离线并广播
        if (!hasAnyOnlineDevice(userId)) {
            onlineStatusService.userOffline(userId);
            notifyFriends(userId, "offline:" + userId);
            endCallOnOffline(userId);
        }
    }

    /**
     * 用户全部设备离线时结束其进行中的通话并通知对端，避免对端一直振铃/幽灵通话。
     * 状态：通话已接通(ACTIVE)→COMPLETED；主叫未接通即掉线→CANCELED；被叫未接即掉线→MISSED。
     */
    private void endCallOnOffline(String userId) {
        try {
            String callId = callService.callOf(userId);
            if (callId == null) {
                return;
            }
            Map<Object, Object> state = callService.state(callId);
            String caller = state.get("callerId") == null ? null : String.valueOf(state.get("callerId"));
            String callee = state.get("calleeId") == null ? null : String.valueOf(state.get("calleeId"));
            String peer = userId.equals(caller) ? callee : caller;
            String status = CallService.STATUS_ACTIVE.equals(callService.statusOf(callId))
                    ? CallSession.STATUS_COMPLETED
                    : (userId.equals(caller) ? CallSession.STATUS_CANCELED : CallSession.STATUS_MISSED);
            callService.terminate(callId, status);
            if (peer != null) {
                ChatMessage hangup = ChatMessage.builder()
                        .type(ChatConstants.CALL_HANGUP)
                        .from(ChatConstants.SYSTEM_USER)
                        .to(peer)
                        .group(false)
                        .content("")
                        .build();
                hangup.addHeader(ChatConstants.HEADER_CALL_ID, callId);
                hangup.addHeader(ChatConstants.HEADER_CALL_REASON, ChatConstants.REASON_PEER_OFFLINE);
                callSignalRouter.deliver(hangup);
            }
            log.info("Call {} ended by offline user {} (status={})", callId, userId, status);
        } catch (Exception e) {
            log.warn("End call on offline failed for user {}: {}", userId, e.getMessage());
        }
    }

    /** 解析连接所属用户；access token 过期时回退解析过期 token，尽量拿到 userId 以清理路由/在线态 */
    private String resolveUserId(Connection connection) {
        String token = (String) connection.attributes().get(tokenName);
        try {
            String userId = JwtUtils.getUserIdFromToken(token, jwtSecret);
            if (userId != null) {
                return userId;
            }
        } catch (Exception ignored) {
            // 可能已过期，走下面的容错解析
        }
        try {
            return JwtUtils.getUserIdFromExpiredToken(token, jwtSecret);
        } catch (Exception e) {
            log.debug("Failed to resolve userId on connection close: {}", e.getMessage());
            return null;
        }
    }

    /** 该用户是否仍有任一在线设备（本节点连接或其他节点的路由） */
    private boolean hasAnyOnlineDevice(String userId) {
        if (!connectionRegistry.getConnections(userId).isEmpty()) {
            return true;
        }
        if (clusterProperties.isEnabled()) {
            try {
                return routeRegistry.isOnline(userId);
            } catch (Exception e) {
                log.debug("Failed to check online devices for user {}: {}", userId, e.getMessage());
            }
        }
        return false;
    }

    /** 集群模式下登记连接路由（未开启集群时为空操作，保持单机行为不变） */
    private void registerRoute(String userId, Connection connection) {
        if (!clusterProperties.isEnabled()) {
            return;
        }
        try {
            routeRegistry.register(userId, ConnAttributes.deviceId(connection),
                    ConnAttributes.deviceType(connection), connection.id(),
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

    private void sendOfflineMessages(String userId, Connection connection) {
        try {
            String deviceId = ConnAttributes.deviceId(connection);
            List<ChatMessage> deviceMessages = offlineMessageService.getOfflineMessages(userId, deviceId);
            List<ChatMessage> userMessages = offlineMessageService.getOfflineMessages(userId);
            int total = deviceMessages.size() + userMessages.size();
            if (total == 0) {
                return;
            }

            log.info("Sending {} offline messages to user {} device {}", total, userId, deviceId);
            if (connection != null && connection.isActive()) {
                for (ChatMessage message : deviceMessages) {
                    connection.write(message);
                }
                for (ChatMessage message : userMessages) {
                    connection.write(message);
                }
            }
        } catch (Exception e) {
            log.error("Failed to send offline messages to user {}", userId, e);
        }
    }
}
