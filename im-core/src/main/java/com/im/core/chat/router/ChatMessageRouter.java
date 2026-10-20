package com.im.core.chat.router;

import com.elaina.chat.core.group.GroupMemberProvider;
import com.elaina.chat.core.message.ChatMessage;
import com.elaina.chat.core.router.MessageRouter;
import com.im.common.constant.ChatConstants;
import com.im.common.utils.ThrowUtils;
import com.im.core.friend.service.FriendService;
import com.im.core.group.mapper.GroupMemberMapper;
import com.im.core.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageRouter implements MessageRouter {

    private final GroupMemberProvider memberProvider;
    private final FriendService friendService;
    private final UserService userService;

    @Override
    public List<String> resolveTargets(ChatMessage message) {
        if (message.isGroup()) {
            List<String> memberIds = memberProvider.getMembers(message.getTo());
            ThrowUtils.throwIf(!(ChatConstants.SYSTEM_USER.equals(message.getFrom()) || memberIds.contains(message.getFrom())), 403, "禁止向未加入的群聊发消息");
            // 跳过已注销成员：不投递、不落库、不进离线队列
            Set<Long> activeIds = userService.filterActiveUserIds(
                    memberIds.stream().map(Long::parseLong).toList());
            return memberIds.stream()
                    .filter(id -> activeIds.contains(Long.parseLong(id)))
                    .toList();
        }
        ThrowUtils.throwIf(!(ChatConstants.SYSTEM_USER.equals(message.getFrom()) || String.valueOf(ChatConstants.SYSTEM_ACCOUNT_ID).equals(message.getFrom()) || friendService.isFriend(Long.parseLong(message.getFrom()), Long.parseLong(message.getTo()))), 403, "禁止向非好友发消息");
        // 对方已注销则禁止发送
        ThrowUtils.throwIf(!ChatConstants.SYSTEM_USER.equals(message.getFrom())
                && userService.isDeleted(Long.parseLong(message.getTo())), 403, "对方账号已注销，无法发送消息");
        return List.of(message.getTo());
    }
}
