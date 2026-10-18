package com.im.core.search.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 聚合搜索结果：四类各自返回总数与当前页条目。
 */
public record SearchResponse(
        Category<ContactItem> contacts,
        Category<GroupItem> groups,
        Category<MemberItem> members,
        Category<MessageItem> messages) {

    public record Category<T>(long total, List<T> items) {
    }

    public record ContactItem(
            Long userId,
            String username,
            String nickname,
            String avatar,
            String signature,
            Integer isFriend) {
    }

    public record GroupItem(
            Long id,
            String name,
            String avatar,
            Long ownerId) {
    }

    public record MemberItem(
            Long userId,
            String username,
            String nickname,
            String avatar,
            Long groupId,
            String groupName,
            Integer role) {
    }

    public record MessageItem(
            String msgId,
            Long seq,
            Long targetId,
            Integer chatType,
            Long senderId,
            String senderName,
            String groupName,
            String content,
            LocalDateTime createdAt) {
    }
}
