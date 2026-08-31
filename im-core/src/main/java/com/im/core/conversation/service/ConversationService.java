package com.im.core.conversation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

    public void updateConversation(Long userId, Long targetId, int chatType, String lastMsg) {
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
            conv.setLastMsgTime(LocalDateTime.now());
            conv.setUnreadCount(1);
            conversationMapper.insert(conv);
        } else {
            conv.setLastMsgContent(lastMsg);
            conv.setLastMsgTime(LocalDateTime.now());
            conv.setUnreadCount(conv.getUnreadCount() + 1);
            conversationMapper.updateById(conv);
        }
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
