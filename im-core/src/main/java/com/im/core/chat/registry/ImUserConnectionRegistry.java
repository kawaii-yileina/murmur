package com.im.core.chat.registry;

import com.elaina.chat.core.connection.Connection;
import com.elaina.chat.core.registry.UserConnectionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户连接注册表（应用侧实现，覆盖框架默认的 InMemoryUserConnectionRegistry）。
 *
 * 修复框架实现的一个缺陷：框架在 register(userId, newConn) 中替换映射并关闭旧连接，
 * 旧连接的 channelInactive 随后调用 unregister(userId)，会把"新连接"的映射一并删除，
 * 导致快速重连后本地直投失效（消息被当作离线）。
 *
 * 修复方式：unregister(userId) 只在"当前映射的连接已失活"时才移除（条件删除）。
 * channelInactive 时被关闭的连接 isActive() 为 false，而若当前映射仍是活跃的新连接，
 * 说明该下线事件来自被顶替的旧连接，不应删除新连接。
 */
@Slf4j
@Component
public class ImUserConnectionRegistry implements UserConnectionRegistry {

    private final Map<String, Connection> userConnections = new ConcurrentHashMap<>();

    @Override
    public void register(String userId, Connection connection) {
        Connection prev = userConnections.put(userId, connection);
        if (prev != null && prev != connection && prev.isActive()) {
            log.debug("Supersede connection for user {}: old={}, new={}", userId, prev.id(), connection.id());
            prev.close();
        }
    }

    @Override
    public void unregister(String userId) {
        Connection current = userConnections.get(userId);
        if (current == null) {
            return;
        }
        // 仅当当前映射的连接确已失活才移除；否则为被顶替旧连接的下线事件，不能误删新连接
        if (current.isActive()) {
            log.debug("Ignore stale unregister for user {} (current connection {} still active)", userId, current.id());
            return;
        }
        userConnections.remove(userId, current);
    }

    @Override
    public Connection getConnection(String userId) {
        return userConnections.get(userId);
    }

    @Override
    public Set<String> getOnlineUsers() {
        return Set.copyOf(userConnections.keySet());
    }
}
