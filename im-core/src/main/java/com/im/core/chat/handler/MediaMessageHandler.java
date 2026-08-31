package com.im.core.chat.handler;

import com.elaina.chat.core.handler.HandlerChain;
import com.elaina.chat.core.handler.MessageHandler;
import com.elaina.chat.core.message.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
public class MediaMessageHandler implements MessageHandler {

    private static final Set<String> MEDIA_TYPES = Set.of("IMAGE", "VIDEO", "FILE", "VOICE");

    @Override
    public void handle(ChatMessage message, HandlerChain chain) {
        if (MEDIA_TYPES.contains(message.getType())) {
            log.debug("Processing media message type={} from {} to {}", 
                message.getType(), message.getFrom(), message.getTo());
        }
        chain.doHandler(message);
    }
}
