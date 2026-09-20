package com.im.core.cluster.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.core.cluster.NodeContext;
import com.im.core.cluster.config.ClusterProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 连接路由表：维护 userId:deviceId 到节点（nodeId）的映射。
 *
 * Redis Key：
 * - im:conn:{userId}:{deviceId}  -> RouteEntry(JSON)，带 TTL，心跳续期
 * - im:user:devices:{userId}     -> 该用户在线设备集合
 * - im:node:{nodeId}             -> 节点存活标记（JSON），带 TTL
 * - im:node:{nodeId}:conns       -> 该节点承载的连接集合，用于优雅下线清理
 *
 * 读取带 2 秒本地缓存，避免热路径每次都打 Redis；跨节点变更由短 TTL 自然收敛。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteRegistry {

    /** 客户端未上报 deviceId 时的缺省值（多端能力在后续批次接入真实 deviceId） */
    public static final String DEFAULT_DEVICE_ID = "default";

    private static final String ROUTE_PREFIX = "im:conn:";
    private static final String DEVICES_PREFIX = "im:user:devices:";
    private static final String NODE_PREFIX = "im:node:";
    private static final String NODE_CONNS_SUFFIX = ":conns";
    private static final long CACHE_TTL_MILLIS = 2000;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final NodeContext nodeContext;
    private final ClusterProperties properties;

    private final ConcurrentHashMap<String, CachedRoute> cache = new ConcurrentHashMap<>();

    // ---------- 连接路由 ----------

    /** 用户上线：登记路由并加入节点连接集合 */
    public void register(String userId, String deviceId, String connId, String platform) {
        RouteEntry entry = new RouteEntry(userId, deviceId, nodeContext.getNodeId(), connId,
                platform, nodeContext.getDefaultDeviceType(), System.currentTimeMillis());
        long ttl = properties.getRouteTtlSeconds();
        redis.opsForValue().set(routeKey(userId, deviceId), writeJson(entry), ttl, TimeUnit.SECONDS);
        redis.opsForSet().add(devicesKey(userId), deviceId);
        redis.expire(devicesKey(userId), ttl * 2, TimeUnit.SECONDS);
        redis.opsForSet().add(nodeConnsKey(nodeContext.getNodeId()), connKey(userId, deviceId));
        invalidate(userId, deviceId);
        log.debug("Route registered: user={}, device={}, node={}", userId, deviceId, nodeContext.getNodeId());
    }

    /** 用户下线：移除路由；该用户已无任何设备时清理设备集合 */
    public void unregister(String userId, String deviceId) {
        redis.delete(routeKey(userId, deviceId));
        redis.opsForSet().remove(devicesKey(userId), deviceId);
        redis.opsForSet().remove(nodeConnsKey(nodeContext.getNodeId()), connKey(userId, deviceId));
        invalidate(userId, deviceId);
        Long size = redis.opsForSet().size(devicesKey(userId));
        if (size != null && size == 0) {
            redis.delete(devicesKey(userId));
        }
        log.debug("Route unregistered: user={}, device={}", userId, deviceId);
    }

    /** 续期单个设备路由的 TTL */
    public void renew(String userId, String deviceId) {
        long ttl = properties.getRouteTtlSeconds();
        redis.expire(routeKey(userId, deviceId), ttl, TimeUnit.SECONDS);
        redis.expire(devicesKey(userId), ttl * 2, TimeUnit.SECONDS);
    }

    /** 续期该用户全部设备路由的 TTL（心跳按用户维度到达，无法区分设备时使用） */
    public void renewAll(String userId) {
        for (String deviceId : deviceIds(userId)) {
            renew(userId, deviceId);
        }
    }

    /** 查询某用户某设备的连接路由，不存在返回 null */
    public RouteEntry find(String userId, String deviceId) {
        String key = routeKey(userId, deviceId);
        CachedRoute cached = cache.get(key);
        if (cached != null && !cached.expired()) {
            return cached.entry();
        }
        String json = redis.opsForValue().get(key);
        RouteEntry entry = json == null ? null : readJson(json);
        if (entry != null) {
            cache.put(key, new CachedRoute(entry, System.currentTimeMillis() + CACHE_TTL_MILLIS));
        } else {
            cache.remove(key);
        }
        return entry;
    }

    /** 该用户在线设备集合 */
    public Set<String> deviceIds(String userId) {
        Set<String> members = redis.opsForSet().members(devicesKey(userId));
        return members == null ? Set.of() : members;
    }

    /** 该用户所有在线设备所在的节点集合（去重），用于跨节点 fan-out */
    public Set<String> nodesOf(String userId) {
        Set<String> nodes = new HashSet<>();
        for (String deviceId : deviceIds(userId)) {
            RouteEntry entry = find(userId, deviceId);
            if (entry != null) {
                nodes.add(entry.getNodeId());
            }
        }
        return nodes;
    }

    /** 该用户是否有任一设备在线 */
    public boolean isOnline(String userId) {
        for (String deviceId : deviceIds(userId)) {
            if (find(userId, deviceId) != null) {
                return true;
            }
        }
        return false;
    }

    // ---------- 节点存活 ----------

    /** 上报本节点存活 */
    public void heartbeatNode() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", nodeContext.getNodeId());
        payload.put("at", System.currentTimeMillis());
        redis.opsForValue().set(nodeKey(nodeContext.getNodeId()), writeJson(payload),
                properties.getNodeTtlSeconds(), TimeUnit.SECONDS);
    }

    public boolean isNodeAlive(String nodeId) {
        return Boolean.TRUE.equals(redis.hasKey(nodeKey(nodeId)));
    }

    /** 优雅下线：清理本节点承载的全部路由与存活标记 */
    public void removeNodeRoutes() {
        String connsKey = nodeConnsKey(nodeContext.getNodeId());
        Set<String> conns = redis.opsForSet().members(connsKey);
        int cleaned = 0;
        if (conns != null) {
            for (String conn : conns) {
                int idx = conn.indexOf(':');
                if (idx <= 0) {
                    continue;
                }
                String userId = conn.substring(0, idx);
                String deviceId = conn.substring(idx + 1);
                redis.delete(routeKey(userId, deviceId));
                redis.opsForSet().remove(devicesKey(userId), deviceId);
                invalidate(userId, deviceId);
                cleaned++;
            }
        }
        redis.delete(connsKey);
        redis.delete(nodeKey(nodeContext.getNodeId()));
        log.info("Node routes removed: node={}, connections={}", nodeContext.getNodeId(), cleaned);
    }

    // ---------- 内部 ----------

    private String routeKey(String userId, String deviceId) {
        return ROUTE_PREFIX + userId + ":" + deviceId;
    }

    private String devicesKey(String userId) {
        return DEVICES_PREFIX + userId;
    }

    private String nodeKey(String nodeId) {
        return NODE_PREFIX + nodeId;
    }

    private String nodeConnsKey(String nodeId) {
        return NODE_PREFIX + nodeId + NODE_CONNS_SUFFIX;
    }

    private String connKey(String userId, String deviceId) {
        return userId + ":" + deviceId;
    }

    private void invalidate(String userId, String deviceId) {
        cache.remove(routeKey(userId, deviceId));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize route entry", e);
        }
    }

    private RouteEntry readJson(String json) {
        try {
            return objectMapper.readValue(json, RouteEntry.class);
        } catch (Exception e) {
            log.warn("Failed to parse route entry: {}", e.getMessage());
            return null;
        }
    }

    private record CachedRoute(RouteEntry entry, long expireAt) {
        boolean expired() {
            return System.currentTimeMillis() > expireAt;
        }
    }
}
