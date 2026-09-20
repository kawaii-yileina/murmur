package com.im.core.message.event;

import com.elaina.chat.core.gateway.MessageGateway;
import com.elaina.chat.core.message.ChatMessage;
import com.im.common.constant.ChatConstants;
import com.im.common.enums.SystemEvent;
import com.im.core.group.service.GroupService;
import com.im.core.message.entity.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 消息撤回通知的消费者：向会话内其他成员推送 SYSTEM 事件（message_recall:msgId）。
 * 该类依赖 MessageGateway/GroupService，但不被任何框架组件依赖，
 * 因此不会参与 MessageGateway → Dispatcher → 扩展点 的构造链。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageRecallNotifier {

    private final MessageGateway messageGateway;
    private final GroupService groupService;

    @Async
    @EventListener
    public void onMessageRecalled(MessageRecalledEvent event) {
        Message msg = event.message();
        String content = SystemEvent.MESSAGE_RECALL.format(msg.getMsgId());
        List<String> targets = msg.getChatType() != null && msg.getChatType() == 1
                ? groupService.getMemberIds(msg.getReceiverId())
                : List.of(String.valueOf(msg.getReceiverId()));
        String operator = String.valueOf(event.operatorId());
        for (String target : targets) {
            if (target.equals(operator)) continue;
            try {
                ChatMessage notification = ChatMessage.builder()
                        .type(ChatConstants.SYSTEM_USER)
                        .from(ChatConstants.SYSTEM_USER)
                        .to(target)
                        .group(false)
                        .content(content)
                        .build();
                messageGateway.sendToUser(target, notification, false);
            } catch (Exception e) {
                log.error("Failed to notify recall to {}: {}", target, e.getMessage());
            }
        }
    }
}
