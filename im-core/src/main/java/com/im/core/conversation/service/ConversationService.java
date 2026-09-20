package com.im.core.conversation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.common.constant.ChatConstants;
import com.im.core.conversation.entity.Conversation;
import com.im.core.conversation.mapper.ConversationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationMapper conversationMapper;

    public List<Conversation> getConversations(Long userId) {
        return conversationMapper.selectList(
            new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .orderByDesc(Conversation::getLastMsgTime)
        );
    }

    public void updateConversation(Long userId, Long targetId, int chatType, String msgId, String lastMsg) {
        updateConversation(userId, targetId, chatType, msgId, lastMsg, false);
    }

    /**
     * 更新会话（最后一条消息 / 未读数）。
     *
     * @param mentioned 本条消息是否 @ 了该用户（群聊），置 1 用于会话列表强提醒
     */
    public void updateConversation(Long userId, Long targetId, int chatType, String msgId,
                                   String lastMsg, boolean mentioned) {
        Conversation conv = conversationMapper.selectOne(
            new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getTargetId, targetId)
                .eq(Conversation::getChatType, chatType)
        );

        if (conv == null) {
            conv = new Conversation();
            conv.setUserId(userId);
            conv.setTargetId(targetId);
            conv.setChatType(chatType);
            conv.setLastMsgContent(lastMsg);
            conv.setLastMsgId(msgId);
            conv.setLastMsgTime(LocalDateTime.now());
            conv.setUnreadCount(1);
            conv.setMentioned(mentioned ? 1 : 0);
            conversationMapper.insert(conv);
        } else {
            conv.setLastMsgContent(lastMsg);
            conv.setLastMsgId(msgId);
            conv.setLastMsgTime(LocalDateTime.now());
            conv.setUnreadCount(conv.getUnreadCount() + 1);
            if (mentioned) {
                conv.setMentioned(1);
            }
            conversationMapper.updateById(conv);
        }
    }

    /**
     * 消息撤回后更新会话预览：仅当被撤回的消息正是该会话最后一条时更新，
     * 避免覆盖掉撤回之后到达的新消息。撤回者本人的会话显示"你撤回"，其余人显示"对方撤回"。
     */
    public void markRecalled(String msgId, Long senderId) {
        Conversation self = new Conversation();
        self.setLastMsgContent(ChatConstants.RECALL_PREVIEW_SELF);
        conversationMapper.update(self, new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getLastMsgId, msgId)
                .eq(Conversation::getUserId, senderId));

        Conversation other = new Conversation();
        other.setLastMsgContent(ChatConstants.RECALL_PREVIEW_OTHER);
        conversationMapper.update(other, new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getLastMsgId, msgId)
                .ne(Conversation::getUserId, senderId));
    }

    public void markAsRead(Long userId, Long targetId, int chatType) {
        Conversation conv = conversationMapper.selectOne(
            new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getTargetId, targetId)
                .eq(Conversation::getChatType, chatType)
        );
        if (conv != null) {
            conv.setUnreadCount(0);
            conv.setMentioned(0);
            conversationMapper.updateById(conv);
        }
    }

    public void deleteConversation(Long userId, Long targetId, int chatType) {
        conversationMapper.delete(
            new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getTargetId, targetId)
                .eq(Conversation::getChatType, chatType)
        );
    }
}
