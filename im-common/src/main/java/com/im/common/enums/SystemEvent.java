package com.im.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * SYSTEM 消息事件类型。
 * content 格式：eventType:data1:data2:...
 */
@Getter
@AllArgsConstructor
public enum SystemEvent {

    // 在线状态（已有）
    ONLINE("online"),
    OFFLINE("offline"),

    // 好友申请（B1）
    FRIEND_REQUEST("friend_request"),
    FRIEND_ACCEPT("friend_accept"),
    FRIEND_REJECT("friend_reject"),

    // 群操作（B2）
    GROUP_ADD("group_add"),
    GROUP_KICK("group_kick"),
    GROUP_QUIT("group_quit"),
    GROUP_TRANSFER("group_transfer"),
    GROUP_RENAME("group_rename"),
    GROUP_ADMIN_SET("group_admin_set"),
    GROUP_ADMIN_UNSET("group_admin_unset"),

    // 消息撤回（A2）
    MESSAGE_RECALL("message_recall"),

    // 会话列表变更（预览/未读/删除）
    CONVERSATION_CHANGED("conversation_changed"),

    // 同类型设备顶号
    SESSION_KICKED("session_kicked"),

    // 发送频率限流
    RATE_LIMITED("rate_limited");

    private final String type;

    /**
     * 构造 SYSTEM 消息 content，格式：eventType:arg1:arg2:...
     */
    public String format(String... args) {
        StringBuilder sb = new StringBuilder(type);
        for (String arg : args) {
            sb.append(':').append(arg);
        }
        return sb.toString();
    }

    /**
     * 从 content 解析事件类型（冒号前第一段）。
     */
    public static String parseType(String content) {
        if (content == null || content.isBlank()) return "";
        int idx = content.indexOf(':');
        return idx > 0 ? content.substring(0, idx) : content;
    }
}
