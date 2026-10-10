package com.im.core.message.util;

import com.elaina.chat.core.message.ChatMessage;

/**
 * 逻辑会话 id：私聊按双方 id 排序（两端一致），群聊为群 id。
 * 用于权威序号分配、Outbox 顺序、投递去重等。
 */
public final class ConversationIds {

    private ConversationIds() {
    }

    public static String of(ChatMessage message) {
        if (message.isGroup()) {
            return "group:" + message.getTo();
        }
        String from = message.getFrom();
        String to = message.getTo();
        if (from == null || to == null) {
            return "private:" + from + ":" + to;
        }
        return from.compareTo(to) <= 0
                ? "private:" + from + ":" + to
                : "private:" + to + ":" + from;
    }
}
