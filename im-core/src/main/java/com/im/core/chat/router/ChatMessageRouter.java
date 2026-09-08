package com.im.core.chat.router;

import com.elaina.chat.core.message.ChatMessage;
import com.elaina.chat.core.router.MessageRouter;
import com.im.common.utils.ThrowUtils;
import com.im.core.friend.service.FriendService;
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
    private final FriendService friendService;

    @Override
    public List<String> resolveTargets(ChatMessage message) {
        if (message.isGroup()) {
            List<String> memberIds = groupService.getMemberIds(Long.parseLong(message.getTo()));
            ThrowUtils.throwIf(!memberIds.contains(message.getFrom()), 403, "禁止向未加入的群聊发消息");
            return memberIds;
        }
        ThrowUtils.throwIf(!friendService.isFriend(Long.parseLong(message.getFrom()), Long.parseLong(message.getTo())), 403, "禁止向非好友发消息");
        return List.of(message.getTo());
    }
}
