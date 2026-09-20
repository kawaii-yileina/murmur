package com.im.core.online.service;

import com.im.core.cluster.config.ClusterProperties;
import com.im.core.cluster.route.RouteRegistry;
import com.im.core.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OnlineStatusService {

    private final StringRedisTemplate redisTemplate;
    private final UserService userService;
    private final RouteRegistry routeRegistry;
    private final ClusterProperties clusterProperties;

    private static final String ONLINE_KEY_PREFIX = "im:online:";
    private static final long TTL_SECONDS = 60;

    public void userOnline(String userId) {
        redisTemplate.opsForValue().set(ONLINE_KEY_PREFIX + userId, "1", TTL_SECONDS, TimeUnit.SECONDS);
        userService.updateStatus(Long.parseLong(userId), 1);
    }

    public void userOffline(String userId) {
        redisTemplate.delete(ONLINE_KEY_PREFIX + userId);
        userService.updateStatus(Long.parseLong(userId), 0);
    }

    public void refreshHeartbeat(String userId) {
        redisTemplate.opsForValue().set(ONLINE_KEY_PREFIX + userId, "1", TTL_SECONDS, TimeUnit.SECONDS);
    }

    public boolean isOnline(String userId) {
        // 集群模式下以连接路由表为在线真值（可感知连接在其他节点的用户）
        if (clusterProperties.isEnabled()) {
            return routeRegistry.isOnline(userId);
        }
        return redisTemplate.hasKey(ONLINE_KEY_PREFIX + userId);
    }

    public List<String> getOnlineFriendIds(String userId) {
        List<Long> friendIds = userService.getFriendIds(Long.parseLong(userId));
        return friendIds.stream()
                .filter(friendId -> isOnline(String.valueOf(friendId)))
                .map(String::valueOf)
                .collect(Collectors.toList());
    }
}
