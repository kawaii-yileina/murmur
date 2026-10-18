package com.im.core.conversation.event;

import com.im.core.conversation.entity.Conversation;

/**
 * 会话快照变更。由 ConversationService 在落库后发布，
 * 由独立监听器向该用户的所有在线设备推送 SYSTEM 事件。
 */
public record ConversationChangedEvent(Conversation conversation, Action action) {

    public enum Action {
        UPSERT,
        DELETE
    }
}
