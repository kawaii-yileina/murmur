package com.im.core.conversation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.common.constant.ChatConstants;
import com.im.core.conversation.entity.Conversation;
import com.im.core.conversation.event.ConversationChangedEvent;
import com.im.core.conversation.mapper.ConversationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationMapper conversationMapper;
    private final ApplicationEventPublisher eventPublisher;

    public List<Conversation> getConversations(Long userId) {
        List<Conversation> conversations = conversationMapper.selectList(
            new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .orderByDesc(Conversation::getLastMsgTime)
        );
        conversations.forEach(this::stamp);
        return conversations;
    }

    public Conversation updateConversation(Long userId, Long targetId, int chatType, String msgId, String lastMsg) {
        return updateConversation(userId, targetId, chatType, msgId, lastMsg, false, false);
    }

    /**
     * 更新会话（最后一条消息 / 未读数），并在落库后推送会话变更事件。
     * 收件人未读 +1；发送者自己的会话不增加未读。
     */
    public Conversation updateConversation(Long userId, Long targetId, int chatType, String msgId,
                                           String lastMsg, boolean mentioned) {
        return updateConversation(userId, targetId, chatType, msgId, lastMsg, mentioned, true);
    }

    private Conversation updateConversation(Long userId, Long targetId, int chatType, String msgId,
                                            String lastMsg, boolean mentioned, boolean incrementUnread) {
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
            conv.setUnreadCount(incrementUnread ? 1 : 0);
            conv.setMentioned(mentioned ? 1 : 0);
            conversationMapper.insert(conv);
        } else {
            conv.setLastMsgContent(lastMsg);
            conv.setLastMsgId(msgId);
            conv.setLastMsgTime(LocalDateTime.now());
            conv.setUnreadCount((conv.getUnreadCount() == null ? 0 : conv.getUnreadCount())
                    + (incrementUnread ? 1 : 0));
            if (mentioned) {
                conv.setMentioned(1);
            }
            conversationMapper.updateById(conv);
        }
        publish(conv, ConversationChangedEvent.Action.UPSERT);
        return conv;
    }

    /**
     * 消息撤回后更新会话预览：仅当被撤回的消息正是该会话最后一条时更新，
     * 避免覆盖撤回之后到达的新消息；随后推送受影响的会话快照。
     */
    public void markRecalled(String msgId, Long senderId) {
        List<Conversation> affected = conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>().eq(Conversation::getLastMsgId, msgId));

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

        for (Conversation affectedConv : affected) {
            Conversation conv = conversationMapper.selectOne(
                    new LambdaQueryWrapper<Conversation>()
                            .eq(Conversation::getUserId, affectedConv.getUserId())
                            .eq(Conversation::getTargetId, affectedConv.getTargetId())
                            .eq(Conversation::getChatType, affectedConv.getChatType()));
            if (conv == null || !msgId.equals(conv.getLastMsgId())) {
                continue;
            }
            conv.setLastMsgContent(senderId.equals(conv.getUserId())
                    ? ChatConstants.RECALL_PREVIEW_SELF
                    : ChatConstants.RECALL_PREVIEW_OTHER);
            publish(conv, ConversationChangedEvent.Action.UPSERT);
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
            conv.setMentioned(0);
            conversationMapper.updateById(conv);
            publish(conv, ConversationChangedEvent.Action.UPSERT);
        }
    }

    public void deleteConversation(Long userId, Long targetId, int chatType) {
        Conversation conv = conversationMapper.selectOne(
            new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getTargetId, targetId)
                .eq(Conversation::getChatType, chatType)
        );
        conversationMapper.delete(
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
        }
        publish(conv, ConversationChangedEvent.Action.DELETE);
    }

    private void publish(Conversation conversation, ConversationChangedEvent.Action action) {
        stamp(conversation);
        Runnable publish = () -> eventPublisher.publishEvent(
                new ConversationChangedEvent(conversation, action));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    private void stamp(Conversation conversation) {
        if (conversation.getLastMsgTime() != null) {
            conversation.setLastMsgTimeMs(conversation.getLastMsgTime()
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
    }
}
