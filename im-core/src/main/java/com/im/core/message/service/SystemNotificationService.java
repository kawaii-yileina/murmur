package com.im.core.message.service;

import com.elaina.chat.core.gateway.MessageGateway;
import com.elaina.chat.core.message.ChatMessage;
import com.im.common.constant.ChatConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 业务通知发送器：在业务事务提交后写入系统通知，避免通知失败污染业务事务，
 * 也避免业务回滚后留下不存在的通知。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemNotificationService {

    private final SystemMessageService systemMessageService;
    private final MessageGateway messageGateway;

    public void sendAfterCommit(Long userId, String text, String eventContent) {
        runAfterCommit(() -> {
            try {
                systemMessageService.sendNotification(userId, text, eventContent);
            } catch (Exception e) {
                log.error("Failed to persist notification for user {}, event={}: {}",
                        userId, eventContent, e.getMessage());
            }
        });
    }

    /** 只推送 SYSTEM 事件、不写系统通知会话；用于建群等只需刷新列表的事件。 */
    public void sendEventAfterCommit(Long userId, String eventContent) {
        runAfterCommit(() -> {
            try {
                ChatMessage event = ChatMessage.builder()
                        .type(ChatConstants.MSG_TYPE_SYSTEM)
                        .from(ChatConstants.SYSTEM_USER)
                        .to(String.valueOf(userId))
                        .group(false)
                        .content(eventContent)
                        .build();
                messageGateway.sendToUser(String.valueOf(userId), event, false);
            } catch (Exception e) {
                log.error("Failed to push system event to user {}: {}", userId, e.getMessage());
            }
        });
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
