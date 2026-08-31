package com.im.core.chat.handler;

import com.elaina.chat.core.handler.HandlerChain;
import com.elaina.chat.core.handler.MessageHandler;
import com.elaina.chat.core.message.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TextMessageHandler implements MessageHandler {

    @Override
    public void handle(ChatMessage message, HandlerChain chain) {
        if ("TEXT".equals(message.getType())) {
            log.debug("Processing text message from {} to {}", message.getFrom(), message.getTo());
            chain.doHandler(message);
        } else {
            chain.doHandler(message);
        }
    }
}
