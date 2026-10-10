package com.im.core.chat.registry;

import com.elaina.chat.core.connection.Connection;
import com.elaina.chat.core.registry.UserConnectionRegistry;

import java.util.Collection;

/**
 * 多端连接注册表：在框架 {@link UserConnectionRegistry} 之上提供"按设备"维度的查询与连接感知移除。
 *
 * 背景：框架的注册表按 userId 单连接（见 chat-nexus F-13），无法支持同账号多端在线；
 * 且框架的 {@code unregister(userId)} 不带连接，无法区分设备。本接口在应用侧补齐这两点：
 * - {@link #getConnections(String)} / {@link #getConnection(String, String)} 按设备读取；
 * - {@link #removeConnection(Connection)} 用连接身份精确移除（由 ChatEventListener.onConnectionClose 驱动）。
 */
public interface MultiConnectionRegistry extends UserConnectionRegistry {

    /** 该用户全部在线连接（各设备一条），无则空集合 */
    Collection<Connection> getConnections(String userId);

    /** 该用户指定设备的在线连接，无则 null */
    Connection getConnection(String userId, String deviceId);

    /** 连接感知移除：仅移除与该连接身份匹配的映射（解决旧连接下线误删新连接/多端问题） */
    void removeConnection(Connection connection);
}
