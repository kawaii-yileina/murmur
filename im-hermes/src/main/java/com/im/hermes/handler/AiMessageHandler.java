package com.im.hermes.handler;

import com.elaina.chat.core.handler.HandlerChain;
import com.elaina.chat.core.handler.MessageHandler;
import com.elaina.chat.core.message.ChatMessage;
import com.elaina.chat.core.connection.Connection;
import com.elaina.chat.core.registry.UserConnectionRegistry;
import com.im.common.constant.ChatConstants;
import com.im.hermes.client.HermesProperties;
import com.im.hermes.service.HermesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hermes.enabled", havingValue = "true")
public class AiMessageHandler implements MessageHandler {

    private final HermesService hermesService;
    private final UserConnectionRegistry connectionRegistry;
    private final HermesProperties hermesProperties;

    @Override
    public void handle(ChatMessage message, HandlerChain chain) {
        if (!hermesProperties.isEnabled()) {
            chain.doHandler(message);
            return;
        }

        if (isAiMessage(message)) {
            processAiMessage(message);
        } else {
            chain.doHandler(message);
        }
    }

    private boolean isAiMessage(ChatMessage message) {
        return ChatConstants.AI_ASSISTANT_ID.equals(message.getTo()) && !message.isGroup();
    }

    private void processAiMessage(ChatMessage message) {
        String userId = message.getFrom();
        String userContent = message.getContent();

        log.debug("Processing AI message from user {}", userId);

        try {
            String aiResponse = hermesService.chat(userId, userContent, "ai_" + userId);

            ChatMessage response = ChatMessage.builder()
                    .id(java.util.UUID.randomUUID().toString())
                    .type("TEXT")
                    .from(ChatConstants.AI_ASSISTANT_ID)
                    .to(userId)
                    .group(false)
                    .content(aiResponse)
                    .build();

            Connection connection = connectionRegistry.getConnection(userId);
            if (connection != null && connection.isActive()) {
                connection.write(response);
                log.debug("AI response sent to user {}", userId);
            } else {
                log.warn("User {} not connected, cannot send AI response", userId);
            }
        } catch (Exception e) {
            log.error("Failed to process AI message from user {}", userId, e);
            
            ChatMessage errorMessage = ChatMessage.builder()
                    .id(java.util.UUID.randomUUID().toString())
                    .type("TEXT")
                    .from(ChatConstants.AI_ASSISTANT_ID)
                    .to(userId)
                    .group(false)
                    .content("Sorry, I encountered an error processing your request. Please try again later.")
                    .build();

            Connection connection = connectionRegistry.getConnection(userId);
            if (connection != null && connection.isActive()) {
                connection.write(errorMessage);
            }
        }
    }
}
