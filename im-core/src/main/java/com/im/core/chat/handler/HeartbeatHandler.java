package com.im.core.chat.handler;

import com.elaina.chat.core.handler.HandlerChain;
import com.elaina.chat.core.handler.MessageHandler;
import com.elaina.chat.core.message.ChatMessage;
import com.im.core.online.service.OnlineStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatHandler implements MessageHandler {

    private final OnlineStatusService onlineStatusService;

    @Override
    public void handle(ChatMessage message, HandlerChain chain) {
        if ("HEARTBEAT".equals(message.getType())) {
            String userId = message.getFrom();
            if (userId != null && !userId.isEmpty()) {
                onlineStatusService.refreshHeartbeat(userId);
                log.debug("Heartbeat refreshed for user {}", userId);
            }
            return;
        }
        chain.doHandler(message);
    }
}
