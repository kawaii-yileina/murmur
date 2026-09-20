package com.im.core.message.event;

import com.im.core.message.entity.Message;

/**
 * 消息撤回事件。由 MessageService 在撤回落库后发布，
 * 由 MessageRecallNotifier 异步消费并向会话内其他成员推送 SYSTEM 通知。
 */
public record MessageRecalledEvent(Message message, Long operatorId) {
}
