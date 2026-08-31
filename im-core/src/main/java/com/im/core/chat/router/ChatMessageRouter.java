package com.im.core.chat.router;

import com.elaina.chat.core.message.ChatMessage;
import com.elaina.chat.core.router.MessageRouter;
import com.im.core.group.service.GroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageRouter implements MessageRouter {

    private final GroupService groupService;

    @Override
    public List<String> resolveTargets(ChatMessage message) {
        if (message.isGroup()) {
            return groupService.getMemberIds(Long.parseLong(message.getTo()));
        }
        return List.of(message.getTo());
    }
}
