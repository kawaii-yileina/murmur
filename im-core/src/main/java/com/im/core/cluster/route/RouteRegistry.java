package com.im.core.cluster.route;

import com.im.core.cluster.NodeContext;
import com.im.core.cluster.config.ClusterProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 连接路由表：维护 userId:deviceId 到节点的映射，并用 Lua 保证生命周期操作的原子性。
 *
 * Redis Key：
 * - im:conn:{userId}:{deviceId}   -> Hash（nodeId/connId/epoch/deviceId/platform/deviceType/loginAt）
 * - im:user:devices:{userId}      -> Set（在线设备）
 * - im:conn:epoch:{userId}:{deviceId} -> 登录代次计数器（INCR）
 * - im:node:{nodeId}              -> 节点存活标记（String，带 TTL）
 * - im:node:{nodeId}:conns        -> Set（本节点承载的连接，元素为 userId:deviceId:connId）
 *
 * 原子性要点：
 * - 注册：仅当新 epoch >= 已存 epoch 才写入（防止旧连接的延迟注册覆盖新连接）。
 * - 续期/删除：仅当 nodeId + connId 均匹配时才执行（防止旧连接误续期/误删新连接路由）。
 * - 删除设备路由与维护 im:user:devices 集合在同一脚本内完成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteRegistry {

    /** 客户端未上报 deviceId 时的缺省值（多端能力后续接入真实 deviceId） */
    public static final String DEFAULT_DEVICE_ID = "default";

    private static final String ROUTE_PREFIX = "im:conn:";
    private static final String DEVICES_PREFIX = "im:user:devices:";
    private static final String EPOCH_PREFIX = "im:conn:epoch:";
    private static final String NODE_PREFIX = "im:node:";
    private static final String NODE_CONNS_SUFFIX = ":conns";
    private static final long CACHE_TTL_MILLIS = 2000;

    /**
     * 注册：KEYS[1]=route KEYS[2]=devices KEYS[3]=nodeConns
     * ARGV[1]=nodeId [2]=connId [3]=epoch [4]=deviceId [5]=platform [6]=deviceType
     *      [7]=loginAt [8]=routeTtl [9]=devicesTtl [10]=connKey [11]=nodeConnsTtl
     * 返回 1=接受，0=被更新的登录代次拒绝。
     */
    private static final String REGISTER_LUA = """
            local route = KEYS[1]
            if redis.call('EXISTS', route) == 1 then
              local t = redis.call('TYPE', route)
              if type(t) == 'table' then t = t['ok'] end
              if t ~= 'hash' then redis.call('DEL', route) end
            end
            local cur = redis.call('HGET', route, 'epoch')
            if cur and tonumber(cur) > tonumber(ARGV[3]) then
              return 0
            end
            redis.call('HSET', route, 'nodeId', ARGV[1], 'connId', ARGV[2], 'epoch', ARGV[3],
                       'deviceId', ARGV[4], 'platform', ARGV[5], 'deviceType', ARGV[6], 'loginAt', ARGV[7])
            redis.call('EXPIRE', route, ARGV[8])
            redis.call('SADD', KEYS[2], ARGV[4])
            if tonumber(ARGV[9]) > 0 then redis.call('EXPIRE', KEYS[2], ARGV[9]) end
            redis.call('SADD', KEYS[3], ARGV[10])
            if tonumber(ARGV[11]) > 0 then redis.call('EXPIRE', KEYS[3], ARGV[11]) end
            return 1
            """;

    /**
     * 续期：KEYS[1]=route KEYS[2]=devices KEYS[3]=nodeConns
     * ARGV[1]=nodeId [2]=connId [3]=routeTtl [4]=devicesTtl [5]=connKey [6]=nodeConnsTtl
     * 仅当 nodeId + connId 均匹配才续期；返回 1=已续期，0=非所有者。
     */
    private static final String RENEW_LUA = """
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            if redis.call('HGET', KEYS[1], 'nodeId') == ARGV[1] and redis.call('HGET', KEYS[1], 'connId') == ARGV[2] then
              redis.call('EXPIRE', KEYS[1], ARGV[3])
              if tonumber(ARGV[4]) > 0 and redis.call('EXISTS', KEYS[2]) == 1 then redis.call('EXPIRE', KEYS[2], ARGV[4]) end
              if tonumber(ARGV[6]) > 0 then
                redis.call('SADD', KEYS[3], ARGV[5])
                redis.call('EXPIRE', KEYS[3], ARGV[6])
              end
              return 1
            end
            return 0
            """;

    /**
     * 下线：KEYS[1]=route KEYS[2]=devices KEYS[3]=nodeConns
     * ARGV[1]=nodeId [2]=connId [3]=deviceId [4]=connKey
     * 仅当 nodeId + connId 均匹配才删除路由并维护设备集合；始终移除本连接在 nodeConns 的条目。
     * 返回 1=拥有并删除，0=非所有者（仅清理 nodeConns）。
     */
    private static final String UNREGISTER_LUA = """
            local owned = 0
            if redis.call('EXISTS', KEYS[1]) == 1
               and redis.call('HGET', KEYS[1], 'nodeId') == ARGV[1]
               and redis.call('HGET', KEYS[1], 'connId') == ARGV[2] then
              redis.call('DEL', KEYS[1])
              redis.call('SREM', KEYS[2], ARGV[3])
              if redis.call('SCARD', KEYS[2]) == 0 then redis.call('DEL', KEYS[2]) end
              owned = 1
            end
            redis.call('SREM', KEYS[3], ARGV[4])
            return owned
            """;

    /**
     * 节点级清理（优雅下线）：KEYS[1]=route KEYS[2]=devices KEYS[3]=nodeConns
     * ARGV[1]=nodeId [2]=deviceId [3]=connKey
     * 仅当路由仍属于本节点时才删除（避免删掉已被其他节点接管的路由）。
     */
    private static final String DELETE_BY_NODE_LUA = """
            if redis.call('EXISTS', KEYS[1]) == 1 and redis.call('HGET', KEYS[1], 'nodeId') == ARGV[1] then
              redis.call('DEL', KEYS[1])
              redis.call('SREM', KEYS[2], ARGV[2])
              if redis.call('SCARD', KEYS[2]) == 0 then redis.call('DEL', KEYS[2]) end
            end
            redis.call('SREM', KEYS[3], ARGV[3])
            return 1
            """;

    private static final RedisScript<Long> REGISTER_SCRIPT = RedisScript.of(REGISTER_LUA, Long.class);
    private static final RedisScript<Long> RENEW_SCRIPT = RedisScript.of(RENEW_LUA, Long.class);
    private static final RedisScript<Long> UNREGISTER_SCRIPT = RedisScript.of(UNREGISTER_LUA, Long.class);
    private static final RedisScript<Long> DELETE_BY_NODE_SCRIPT = RedisScript.of(DELETE_BY_NODE_LUA, Long.class);

    private final StringRedisTemplate redis;
    private final NodeContext nodeContext;
    private final ClusterProperties properties;

    private final ConcurrentHashMap<String, CachedRoute> cache = new ConcurrentHashMap<>();
    /** 节点存活短缓存（仅缓存正结果，避免每个目标都打 Redis） */
    private final ConcurrentHashMap<String, Long> nodeAliveCache = new ConcurrentHashMap<>();

    // ---------- 连接路由 ----------

    /** 用户上线：登记路由并维护设备集合（epoch 大者胜） */
    public void register(String userId, String deviceId, String deviceType, String connId, String platform) {
        long ttl = properties.getRouteTtlSeconds();
        String epochKey = epochKey(userId, deviceId);
        Long epoch = redis.opsForValue().increment(epochKey);
        redis.expire(epochKey, ttl * 10, TimeUnit.SECONDS);
        long now = System.currentTimeMillis();
        String effectiveType = deviceType != null && !deviceType.isBlank()
                ? deviceType : nodeContext.getDefaultDeviceType();

        Long result = redis.execute(REGISTER_SCRIPT,
                List.of(routeKey(userId, deviceId), devicesKey(userId), nodeConnsKey(nodeContext.getNodeId())),
                nodeContext.getNodeId(), connId, String.valueOf(epoch), deviceId, platform,
                effectiveType, String.valueOf(now), String.valueOf(ttl),
                String.valueOf(ttl * 2), connKey(userId, deviceId, connId), String.valueOf(ttl * 10));
        invalidate(userId, deviceId);

        if (result != null && result == 1L) {
            log.debug("Route registered: user={}, device={}, node={}, epoch={}", userId, deviceId, nodeContext.getNodeId(), epoch);
        } else {
            log.warn("Route register rejected by newer login: user={}, device={}, epoch={}", userId, deviceId, epoch);
        }
    }

    /** 用户下线：仅当当前路由仍属于本连接时才删除（否则为旧连接的下线事件） */
    public void unregister(String userId, String deviceId, String connId) {
        Long result = redis.execute(UNREGISTER_SCRIPT,
                List.of(routeKey(userId, deviceId), devicesKey(userId), nodeConnsKey(nodeContext.getNodeId())),
                nodeContext.getNodeId(), connId, deviceId, connKey(userId, deviceId, connId));
        invalidate(userId, deviceId);
        log.debug("Route unregister: user={}, device={}, owned={}", userId, deviceId, result);
    }

    /** 续期：仅当 nodeId + connId 均匹配时生效；同时刷新 nodeConns 存活 */
    public boolean renew(String userId, String deviceId, String connId) {
        long ttl = properties.getRouteTtlSeconds();
        Long result = redis.execute(RENEW_SCRIPT,
                List.of(routeKey(userId, deviceId), devicesKey(userId), nodeConnsKey(nodeContext.getNodeId())),
                nodeContext.getNodeId(), connId, String.valueOf(ttl), String.valueOf(ttl * 2),
                connKey(userId, deviceId, connId), String.valueOf(ttl * 10));
        return result != null && result == 1L;
    }

    /** 查询某用户某设备的连接路由，不存在返回 null */
    public RouteEntry find(String userId, String deviceId) {
        String key = routeKey(userId, deviceId);
        CachedRoute cached = cache.get(key);
        if (cached != null && !cached.expired()) {
            return cached.entry();
        }
        Map<Object, Object> fields = redis.opsForHash().entries(key);
        RouteEntry entry = fields.isEmpty() ? null : toRouteEntry(userId, deviceId, fields);
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
        redis.opsForValue().set(nodeKey(nodeContext.getNodeId()), String.valueOf(payload),
                properties.getNodeTtlSeconds(), TimeUnit.SECONDS);
    }

    public boolean isNodeAlive(String nodeId) {
        if (nodeId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long expireAt = nodeAliveCache.get(nodeId);
        if (expireAt != null && expireAt > now) {
            return true;
        }
        boolean alive = Boolean.TRUE.equals(redis.hasKey(nodeKey(nodeId)));
        if (alive) {
            nodeAliveCache.put(nodeId, now + CACHE_TTL_MILLIS);
        } else {
            nodeAliveCache.remove(nodeId);
        }
        return alive;
    }

    /** 优雅下线：清理仍属于本节点的全部路由与存活标记 */
    public void removeNodeRoutes() {
        String connsKey = nodeConnsKey(nodeContext.getNodeId());
        Set<String> conns = redis.opsForSet().members(connsKey);
        int cleaned = 0;
        if (conns != null) {
            for (String conn : conns) {
                String[] parts = splitConnKey(conn);
                if (parts == null) {
                    continue;
                }
                redis.execute(DELETE_BY_NODE_SCRIPT,
                        List.of(routeKey(parts[0], parts[1]), devicesKey(parts[0]), connsKey),
                        nodeContext.getNodeId(), parts[1], conn);
                invalidate(parts[0], parts[1]);
                cleaned++;
            }
        }
        redis.delete(connsKey);
        redis.delete(nodeKey(nodeContext.getNodeId()));
        log.info("Node routes removed: node={}, connections={}", nodeContext.getNodeId(), cleaned);
    }

    // ---------- 内部 ----------

    private RouteEntry toRouteEntry(String userId, String deviceId, Map<Object, Object> fields) {
        RouteEntry entry = new RouteEntry();
        entry.setUserId(userId);
        entry.setDeviceId(deviceId);
        entry.setNodeId(str(fields.get("nodeId")));
        entry.setConnId(str(fields.get("connId")));
        entry.setEpoch(parseLong(fields.get("epoch")));
        entry.setPlatform(str(fields.get("platform")));
        entry.setDeviceType(str(fields.get("deviceType")));
        entry.setLoginAt(parseLong(fields.get("loginAt")));
        return entry;
    }

    private String str(Object value) {
        return value == null ? null : value.toString();
    }

    private long parseLong(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 解析 nodeConns 元素 userId:deviceId:connId */
    private String[] splitConnKey(String conn) {
        int first = conn.indexOf(':');
        if (first <= 0) {
            return null;
        }
        int second = conn.indexOf(':', first + 1);
        if (second <= first) {
            return null;
        }
        return new String[]{conn.substring(0, first), conn.substring(first + 1, second), conn.substring(second + 1)};
    }

    private String routeKey(String userId, String deviceId) {
        return ROUTE_PREFIX + userId + ":" + deviceId;
    }

    private String devicesKey(String userId) {
        return DEVICES_PREFIX + userId;
    }

    private String epochKey(String userId, String deviceId) {
        return EPOCH_PREFIX + userId + ":" + deviceId;
    }

    private String nodeKey(String nodeId) {
        return NODE_PREFIX + nodeId;
    }

    private String nodeConnsKey(String nodeId) {
        return NODE_PREFIX + nodeId + NODE_CONNS_SUFFIX;
    }

    private String connKey(String userId, String deviceId, String connId) {
        return userId + ":" + deviceId + ":" + connId;
    }

    private void invalidate(String userId, String deviceId) {
        cache.remove(routeKey(userId, deviceId));
    }

    private record CachedRoute(RouteEntry entry, long expireAt) {
        boolean expired() {
            return System.currentTimeMillis() > expireAt;
        }
    }
}
