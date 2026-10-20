package com.im.core.chat.listener;

import com.elaina.chat.core.event.ChatEventListener;
import com.elaina.chat.core.connection.Connection;
import com.elaina.chat.core.message.ChatMessage;
import com.elaina.chat.transport.util.ConnectionUtils;
import com.im.core.chat.registry.MultiConnectionRegistry;
import com.im.core.chat.router.RealtimeMessageRouter;
import com.im.common.utils.JwtUtils;
import com.im.common.constant.ChatConstants;
import com.im.core.call.CallService;
import com.im.core.call.CallSignalRouter;
import com.im.core.call.entity.CallSession;
import com.im.core.cluster.ConnAttributes;
import com.im.core.cluster.config.ClusterProperties;
import com.im.core.cluster.route.RouteRegistry;
import com.im.core.message.service.OfflineMessageService;
import com.im.core.message.service.SystemMessageService;
import com.im.core.online.service.OnlineStatusService;
import com.im.core.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class StatusEventListener implements ChatEventListener {

    private final OnlineStatusService onlineStatusService;
    private final OfflineMessageService offlineMessageService;
    private final MultiConnectionRegistry connectionRegistry;
    private final RealtimeMessageRouter realtimeMessageRouter;
    private final UserService userService;
    private final RouteRegistry routeRegistry;
    private final ClusterProperties clusterProperties;
    private final CallService callService;
    private final CallSignalRouter callSignalRouter;
    private final SystemMessageService systemMessageService;

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
        connection.attributes().put(ChatConstants.ATTR_AUTHED_USER_ID, userId);
        onlineStatusService.userOnline(userId);
        // 顶号判断必须在登记新路由之前完成，否则同一 deviceId 的旧路由已被覆盖
        handleDeviceLogin(userId, connection);
        registerRoute(userId, connection);
        notifyFriends(userId, "online:" + userId);
        sendOfflineMessages(userId, connection);
    }

    /**
     * 同类型设备顶号：先通知旧连接，再关闭；不同类型设备不顶号，改为系统会话提醒。
     * 设备类型只分 mobile / desktop。集群模式下同时检查本节点连接和其他节点路由。
     */
    private void handleDeviceLogin(String userId, Connection connection) {
        String newType = ConnAttributes.normalizedDeviceType(connection);
        boolean hasOtherType = false;
        java.util.Set<String> kickedDevices = new java.util.HashSet<>();

        for (Connection other : connectionRegistry.getConnections(userId)) {
            if (other == null || other.id().equals(connection.id())) {
                continue;
            }
            String type = ConnAttributes.normalizedDeviceType(other);
            if (newType.equals(type)) {
                kickLocal(userId, other, newType);
                kickedDevices.add(ConnAttributes.deviceId(other));
            } else {
                hasOtherType = true;
            }
        }

        if (clusterProperties.isEnabled()) {
            try {
                for (String deviceId : routeRegistry.deviceIds(userId)) {
                    if (kickedDevices.contains(deviceId)) {
                        continue;
                    }
                    com.im.core.cluster.route.RouteEntry entry = routeRegistry.find(userId, deviceId);
                    if (entry == null || connection.id().equals(entry.getConnId())) {
                        continue;
                    }
                    String type = ConnAttributes.normalizedDeviceType(entry.getDeviceType());
                    if (newType.equals(type)) {
                        realtimeMessageRouter.kickDevice(userId, deviceId, kickMessage(userId));
                        kickedDevices.add(deviceId);
                    } else {
                        hasOtherType = true;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to inspect cluster device routes for user {}: {}", userId, e.getMessage());
            }
        }

        if (hasOtherType) {
            String platform = "mobile".equals(newType) ? "手机" : "电脑";
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            systemMessageService.sendToUser(Long.parseLong(userId),
                    "你的账号于 " + time + " 在一台新设备（" + platform
                            + "）登录。如非本人操作，请及时修改密码。");
        }
    }

    private void kickLocal(String userId, Connection old, String type) {
        try {
            ConnectionUtils.writeSafely(old, kickMessage(userId));
            CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS)
                    .execute(() -> ConnectionUtils.closeSafely(old));
            log.info("Kick same-type device: user={}, type={}, oldConn={}", userId, type, old.id());
        } catch (Exception e) {
            log.warn("Failed to kick old device for user {}: {}", userId, e.getMessage());
            ConnectionUtils.closeSafely(old);
        }
    }

    private ChatMessage kickMessage(String userId) {
        return ChatMessage.builder()
                .type(ChatConstants.MSG_TYPE_SYSTEM)
                .from(ChatConstants.SYSTEM_USER)
                .to(userId)
                .group(false)
                .content(com.im.common.enums.SystemEvent.SESSION_KICKED.format())
                .build();
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
        Object authed = connection.attributes().get(ChatConstants.ATTR_AUTHED_USER_ID);
        if (authed != null && !String.valueOf(authed).isBlank()) {
            return String.valueOf(authed);
        }
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
                    .type(ChatConstants.MSG_TYPE_SYSTEM)
                    .from(ChatConstants.SYSTEM_USER)
                    .content(content)
                    .build();
            for (Long friendId : friendIds) {
                realtimeMessageRouter.sendToUser(String.valueOf(friendId), statusMsg);
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
