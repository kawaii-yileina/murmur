package com.im.core.chat.handler;

import com.elaina.chat.core.handler.HandlerChain;
import com.elaina.chat.core.handler.MessageHandler;
import com.elaina.chat.core.message.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 为客户端入站消息分配服务端唯一 msg_id。
 * 该 id 会随消息下发给接收端、作为 {@code t_message.msg_id} 落库、并通过 ACK 回传发送端，
 * 保证发送端、接收端、数据库三处使用同一个消息标识，撤回/引用等按 msg_id 定位的功能才能命中。
 * 框架的 {@link ChatMessage} 未提供 id setter，故用 builder 重建消息（保留 headers）。
 */
@Component
public class MessageIdAssignHandler implements MessageHandler {

    @Override
    public void handle(ChatMessage message, HandlerChain chain) {
        if (message.getId() != null && !message.getId().isBlank()) {
            chain.doHandler(message);
            return;
        }

        ChatMessage stamped = ChatMessage.builder()
                .id(UUID.randomUUID().toString())
                .type(message.getType())
                .from(message.getFrom())
                .to(message.getTo())
                .group(message.isGroup())
                .content(message.getContent())
                .build();
        if (!message.getHeaders().isEmpty()) {
            stamped = stamped.withHeaders(new ConcurrentHashMap<>(message.getHeaders()));
        }
        chain.doHandler(stamped);
    }
}
