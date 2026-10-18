package com.im.core.message.service;

import com.elaina.chat.core.gateway.MessageGateway;
import com.elaina.chat.core.message.ChatMessage;
import com.im.common.constant.ChatConstants;
import com.im.core.conversation.mapper.ConversationSeqMapper;
import com.im.core.conversation.service.ConversationService;
import com.im.core.message.entity.Message;
import com.im.core.message.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 系统通知会话：以负数系统账号给单个用户发送可持久化的 TEXT 消息。
 * 复用普通私聊历史/增量同步，客户端只读展示。
 */
@Service
@RequiredArgsConstructor
public class SystemMessageService {

    private final MessageMapper messageMapper;
    private final ConversationSeqMapper conversationSeqMapper;
    private final ConversationService conversationService;
    private final MessageGateway messageGateway;

    @Transactional
    public void sendToUser(Long userId, String content) {
        String from = String.valueOf(ChatConstants.SYSTEM_ACCOUNT_ID);
        String to = String.valueOf(userId);
        String conversationId = from.compareTo(to) <= 0
                ? "private:" + from + ":" + to
                : "private:" + to + ":" + from;
        conversationSeqMapper.bump(conversationId);
        Long lastSeq = conversationSeqMapper.lastSeq(conversationId);
        long seq = lastSeq == null ? 1L : lastSeq;

        String msgId = UUID.randomUUID().toString();
        Message message = new Message();
        message.setMsgId(msgId);
        message.setSenderId(ChatConstants.SYSTEM_ACCOUNT_ID);
        message.setReceiverId(userId);
        message.setChatType(ChatConstants.CHAT_TYPE_PRIVATE);
        message.setMsgType(ChatConstants.MSG_TYPE_TEXT);
        message.setContent(content);
        message.setSeq(seq);
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);

        conversationService.updateConversation(
                userId, ChatConstants.SYSTEM_ACCOUNT_ID, ChatConstants.CHAT_TYPE_PRIVATE, msgId, content);

        ChatMessage notification = ChatMessage.builder()
                .id(msgId)
                .type(ChatConstants.MSG_TYPE_TEXT)
                .from(from)
                .to(to)
                .group(false)
                .content(content)
                .build()
                .withHeaders(Map.<String, Object>of(ChatConstants.HEADER_SEQ, String.valueOf(seq)));

        Runnable push = () -> messageGateway.sendToUser(to, notification, false);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    push.run();
                }
            });
        } else {
            push.run();
        }
    }
}
