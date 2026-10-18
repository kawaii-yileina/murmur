package com.im.core.conversation.event;

import com.elaina.chat.core.gateway.MessageGateway;
import com.elaina.chat.core.message.ChatMessage;
import com.im.common.constant.ChatConstants;
import com.im.common.enums.SystemEvent;
import com.im.core.conversation.entity.Conversation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 向用户所有在线设备推送会话列表变更，客户端按单条会话更新，不再整表拉取。
 * 不带持久化（sendToUser 三参数 false）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationChangedNotifier {

    private final MessageGateway messageGateway;

    @Async
    @EventListener
    public void onConversationChanged(ConversationChangedEvent event) {
        Conversation conversation = event.conversation();
        if (conversation == null || conversation.getUserId() == null) {
            return;
        }
        try {
            String content = SystemEvent.CONVERSATION_CHANGED.format(
                    String.valueOf(conversation.getTargetId()),
                    String.valueOf(conversation.getChatType()),
                    event.action().name());
            Map<String, Object> headers = new HashMap<>();
            headers.put("targetId", String.valueOf(conversation.getTargetId()));
            headers.put("chatType", String.valueOf(conversation.getChatType()));
            headers.put("action", event.action().name());
            headers.put("lastMsgId", conversation.getLastMsgId() == null ? "" : conversation.getLastMsgId());
            headers.put("lastMsgContent", conversation.getLastMsgContent() == null ? "" : conversation.getLastMsgContent());
            headers.put("lastMsgTime", conversation.getLastMsgTime() == null ? "" : conversation.getLastMsgTime().toString());
            headers.put("lastMsgTimeMs", String.valueOf(conversation.getLastMsgTimeMs() == null ? 0L : conversation.getLastMsgTimeMs()));
            headers.put("unreadCount", String.valueOf(conversation.getUnreadCount() == null ? 0 : conversation.getUnreadCount()));
            headers.put("mentioned", String.valueOf(conversation.getMentioned() == null ? 0 : conversation.getMentioned()));

            ChatMessage notification = ChatMessage.builder()
                    .type(ChatConstants.SYSTEM_USER)
                    .from(ChatConstants.SYSTEM_USER)
                    .to(String.valueOf(conversation.getUserId()))
                    .group(false)
                    .content(content)
                    .build()
                    .withHeaders(headers);
            messageGateway.sendToUser(String.valueOf(conversation.getUserId()), notification, false);
        } catch (Exception e) {
            log.error("Failed to push conversation change to {}: {}",
                    conversation.getUserId(), e.getMessage());
        }
    }
}
