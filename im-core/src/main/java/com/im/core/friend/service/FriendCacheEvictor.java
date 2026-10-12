package com.im.core.friend.service;

import com.im.core.cache.CacheNames;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

/**
 * 好友关系变更后的缓存失效：好友 id 列表按用户维度缓存（{@link CacheNames#FRIEND_IDS}），
 * 任一侧关系变化都需失效双方。抽为独立 Bean 以便跨 Service（FriendService、FriendRequestService）复用，
 * 并确保经 Spring 代理触发失效（同类内部自调用不会生效）。
 */
@Service
public class FriendCacheEvictor {

    @CacheEvict(cacheNames = CacheNames.FRIEND_IDS, key = "#userId")
    public void evictFriendIds(Long userId) {
    }
}
