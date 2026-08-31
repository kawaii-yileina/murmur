package com.im.core.chat.sender;

import com.elaina.chat.core.message.ChatMessage;
import com.elaina.chat.core.router.AbstractSender;
import com.elaina.chat.core.router.SendResult;
import com.elaina.chat.core.connection.Connection;
import com.elaina.chat.core.registry.UserConnectionRegistry;
import com.elaina.chat.core.offline.OfflineHandler;
import com.im.core.message.service.OfflineMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class ChatMessageSender extends AbstractSender {

    private final OfflineMessageService offlineMessageService;

    @Autowired
    public ChatMessageSender(UserConnectionRegistry registry, OfflineHandler offlineHandler,
                           OfflineMessageService offlineMessageService) {
        super(registry, offlineHandler);
        this.offlineMessageService = offlineMessageService;
    }

    @Override
    public SendResult send(ChatMessage message, List<String> recipients, Connection sender) {
        List<String> delivered = new ArrayList<>();
        List<String> offline = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (String recipientId : recipients) {
            Connection conn = registry.getConnection(recipientId);
            if (conn != null && conn.isActive()) {
                conn.write(message);
                delivered.add(recipientId);
            } else {
                offline.add(recipientId);
                offlineMessageService.storeOfflineMessage(recipientId, message);
                notifyOffline(message, recipientId, sender);
            }
        }

        return SendResult.of(delivered, offline, failed);
    }
}
