package com.im.core.chat.hook;

import com.elaina.chat.core.message.ChatMessage;
import com.elaina.chat.core.persistence.MessagePersistentHook;
import com.elaina.chat.core.registry.UserConnectionRegistry;
import com.im.common.constant.ChatConstants;
import com.im.common.utils.MentionsUtils;
import com.im.core.conversation.service.ConversationService;
import com.im.core.message.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessagePersistHook implements MessagePersistentHook {

    private final MessageService messageService;
    private final ConversationService conversationService;
    private final UserConnectionRegistry connectionRegistry;

    private static final Set<String> SKIP_TYPES = Set.of("SYSTEM", "HEARTBEAT");

    @Override
    @Async
    @Transactional(rollbackFor = Exception.class)
    public void afterSend(ChatMessage msg, List<String> recipients, List<String> successList) {
        try {
            String type = msg.getType();
            if (SKIP_TYPES.contains(type)) {
                return;
            }

            Long senderId = parseLong(msg.getFrom());
            Long receiverId = parseLong(msg.getTo());
            if (senderId == null || receiverId == null) {
                log.warn("Skipping persist for message with non-numeric ids: from={}, to={}", msg.getFrom(), msg.getTo());
                return;
            }

            messageService.persistMessage(msg, successList);

            int chatType = msg.isGroup() ? 1 : 0;
            String lastMsgContent = truncateMessage(msg);

            Long recipientTargetId = msg.isGroup() ? receiverId : senderId;

            // 群聊 @提及：解析被 @ 成员，用于置会话强提醒
            Set<String> mentionedUserIds = chatType == 1
                    ? MentionsUtils.extractUserIds(getHeader(msg, ChatConstants.HEADER_MENTIONS))
                    : Set.of();
            boolean mentionAll = mentionedUserIds.contains(ChatConstants.MENTION_ALL);

            // 用 recipients（全部目标）而非 successList（仅在线送达）更新会话：
            // 离线接收方的消息会进离线队列，其会话预览/未读数也必须同步，
            // 否则同一会话两端显示的"最后一条消息"会不一致。
            for (String recipientId : recipients) {
                Long rid = parseLong(recipientId);
                if (rid != null) {
                    boolean mentioned = mentionAll || mentionedUserIds.contains(recipientId);
                    conversationService.updateConversation(
                            rid, recipientTargetId, chatType, msg.getId(), lastMsgContent, mentioned);
                }
            }

            conversationService.updateConversation(senderId, receiverId, chatType, msg.getId(), lastMsgContent);
            sendAckToSender(msg, successList);

        } catch (Exception e) {
            log.error("Failed to persist message: {}", msg.getId(), e);
        }
    }

    /**
     * 持久化成功后向发送者发送 ACK：
     * SYSTEM 类型消息，headers 携带 ack=true、clientMsgId、serverMsgId、delivered。
     * 前端据此更新本地消息状态为 sent，替换 msgId 为服务端 id。
     */
    private void sendAckToSender(ChatMessage msg, List<String> successList) {
        try {
            String clientMsgId = msg.getHeaders() != null
                    ? String.valueOf(msg.getHeaders().getOrDefault("clientMsgId", ""))
                    : "";
            if (clientMsgId.isBlank()) {
                return;
            }

            ChatMessage ack = ChatMessage.builder()
                    .type(ChatConstants.SYSTEM_USER)
                    .from(ChatConstants.SYSTEM_USER)
                    .to(msg.getFrom())
                    .group(false)
                    .content("")
                    .build();
            ack.addHeader("ack", "true");
            ack.addHeader("clientMsgId", clientMsgId);
            ack.addHeader("serverMsgId", msg.getId() != null ? msg.getId() : "");
            ack.addHeader("delivered", String.join(",", successList));

            connectionRegistry.getConnection(msg.getFrom()).write(ack);
            log.debug("Sent ACK for clientMsgId={}, serverMsgId={}", clientMsgId, msg.getId());
        } catch (Exception e) {
            log.error("Failed to send ACK for message from {}", msg.getFrom(), e);
        }
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String truncateMessage(ChatMessage msg) {
        String content = msg.getContent();
        String type = msg.getType();
        if (content == null) return "";
        return switch (type) {
            case "IMAGE" -> "[图片]";
            case "VIDEO" -> "[视频]";
            case "VOICE" -> "[语音] " + Math.round(parseDuration(msg)) + "\"";
            case "CONTACT_CARD" -> "[个人名片]";
            case "FILE" -> {
                String name = getHeader(msg, "originalName");
                String text = "[文件] " + name;
                yield text.length() > 50 ? text.substring(0, 50) + "..." : text;
            }
            default -> content.length() > 50 ? content.substring(0, 50) + "..." : content;
        };
    }

    private String getHeader(ChatMessage msg, String key) {
        if (msg.getHeaders() == null) return "";
        Object value = msg.getHeaders().get(key);
        return value != null ? String.valueOf(value) : "";
    }

    private double parseDuration(ChatMessage msg) {
        try {
            return Double.parseDouble(getHeader(msg, "duration"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
