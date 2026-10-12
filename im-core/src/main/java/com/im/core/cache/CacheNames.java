package com.im.core.cache;

/**
 * 缓存名常量（Spring Cache）。集中定义避免注解里散落字符串。
 * 均使用 Redis 分布式缓存（多实例共享），写操作以失效（evict）保证一致性。
 */
public final class CacheNames {

    private CacheNames() {
    }

    /** 好友 id 列表（key=userId） */
    public static final String FRIEND_IDS = "friendIds";
    /** 群成员 id 列表（key=groupId） */
    public static final String GROUP_MEMBERS = "groupMembers";
    /** 群信息（key=groupId） */
    public static final String GROUP_INFO = "groupInfo";
}
