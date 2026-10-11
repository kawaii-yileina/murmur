package com.im.core.chat.registry;

import com.elaina.chat.core.connection.Connection;
import com.im.core.cluster.ConnAttributes;
import com.im.core.cluster.route.RouteRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户连接注册表（应用侧多端实现，覆盖框架默认 InMemoryUserConnectionRegistry）。
 * 结构：userId -> (deviceId -> Connection)，支持同一账号多设备同时在线。
 * - register：按 (userId, deviceId) 存放；同一设备重复登录时顶号（关闭旧连接），不同设备并存。
 * - unregister(userId)：框架回调不带连接，无法区分设备，故只清理"已失活"的连接（活跃连接保留）。
 * - removeConnection(connection)：连接感知移除，由 ChatEventListener.onConnectionClose 驱动，
 *   彻底解决"旧连接下线误删新连接"（F-12）与多端下线问题。
 */
@Slf4j
@Component
public class ImUserConnectionRegistry implements MultiConnectionRegistry {

    private final Map<String, Map<String, Connection>> userDevices = new ConcurrentHashMap<>();
    /** 反查：connId -> (userId, deviceId)，用于连接感知移除 */
    private final Map<String, DeviceKey> connIndex = new ConcurrentHashMap<>();

    @Override
    public void register(String userId, Connection connection) {
        String deviceId = ConnAttributes.deviceId(connection);
        Connection[] superseded = new Connection[1];
        // 用 compute 持 bin 锁原子变更内层 map，避免与 unregister 的空判/摘除产生孤儿 map 竞态
        userDevices.compute(userId, (k, devices) -> {
            Map<String, Connection> map = devices != null ? devices : new ConcurrentHashMap<>();
            Connection prev = map.put(deviceId, connection);
            if (prev != null && prev != connection) {
                superseded[0] = prev;
            }
            return map;
        });
        connIndex.put(connection.id(), new DeviceKey(userId, deviceId));
        Connection prev = superseded[0];
        if (prev != null) {
            connIndex.remove(prev.id());
            if (prev.isActive()) {
                log.debug("Supersede connection for user {} device {}: old={}, new={}",
                        userId, deviceId, prev.id(), connection.id());
                prev.close();
            }
        }
    }

    @Override
    public void unregister(String userId) {
        userDevices.computeIfPresent(userId, (k, devices) -> {
            devices.entrySet().removeIf(e -> {
                Connection c = e.getValue();
                if (c != null && !c.isActive()) {
                    connIndex.remove(c.id());
                    return true;
                }
                return false;
            });
            return devices.isEmpty() ? null : devices;
        });
    }

    @Override
    public void removeConnection(Connection connection) {
        if (connection == null) {
            return;
        }
        DeviceKey key = connIndex.remove(connection.id());
        if (key == null) {
            return;
        }
        userDevices.computeIfPresent(key.userId(), (k, devices) -> {
            devices.remove(key.deviceId(), connection);
            return devices.isEmpty() ? null : devices;
        });
    }

    @Override
    public Connection getConnection(String userId) {
        Map<String, Connection> devices = userDevices.get(userId);
        if (devices == null) {
            return null;
        }
        for (Connection c : devices.values()) {
            if (c != null && c.isActive()) {
                return c;
            }
        }
        return null;
    }

    @Override
    public Connection getConnection(String userId, String deviceId) {
        Map<String, Connection> devices = userDevices.get(userId);
        if (devices == null) {
            return null;
        }
        String key = deviceId != null && !deviceId.isBlank() ? deviceId : RouteRegistry.DEFAULT_DEVICE_ID;
        Connection c = devices.get(key);
        return c != null && c.isActive() ? c : null;
    }

    @Override
    public Collection<Connection> getConnections(String userId) {
        Map<String, Connection> devices = userDevices.get(userId);
        if (devices == null) {
            return List.of();
        }
        List<Connection> result = new ArrayList<>(devices.size());
        for (Connection c : devices.values()) {
            if (c != null && c.isActive()) {
                result.add(c);
            }
        }
        return result;
    }

    @Override
    public Set<String> getOnlineUsers() {
        return Set.copyOf(userDevices.keySet());
    }

    @Override
    public int connectionCount() {
        int count = 0;
        for (Map<String, Connection> devices : userDevices.values()) {
            for (Connection c : devices.values()) {
                if (c != null && c.isActive()) {
                    count++;
                }
            }
        }
        return count;
    }

    private record DeviceKey(String userId, String deviceId) {
    }
}
