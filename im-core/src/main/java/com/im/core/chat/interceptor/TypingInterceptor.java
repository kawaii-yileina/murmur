package com.im.core.chat.interceptor;

import com.elaina.chat.core.handler.HandlerContext;
import com.elaina.chat.core.handler.MessageInterceptor;
import com.elaina.chat.core.message.ChatMessage;
import com.im.core.chat.router.RealtimeMessageRouter;
import com.im.common.constant.ChatConstants;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 输入中状态：瞬时事件，直接投递给在线接收方的全部设备，不落库、不进离线队列。
 * 仅支持私聊（群聊人数多，输入中状态无意义，直接丢弃）。
 * 在 preSend 阶段自行投递并返回 false 阻断后续发送，从而跳过持久化与离线存储。
 * 注意：本类刻意不声明 @Component，原因同 RateLimitInterceptor（避免顶掉框架持久化拦截器），
 * 由 ChatConfiguration 手动构造并注册。
 */
@RequiredArgsConstructor
public class TypingInterceptor implements MessageInterceptor {

    private final RealtimeMessageRouter realtimeRouter;

    @Override
    public boolean preSend(ChatMessage message, HandlerContext ctx, List<String> targets) {
        if (!ChatConstants.MSG_TYPE_TYPING.equals(message.getType())) {
            return true;
        }
        if (message.isGroup()) {
            return false;
        }
        for (String target : targets) {
            if (target.equals(message.getFrom())) {
                continue;
            }
            realtimeRouter.sendToUser(target, message);
        }
        return false;
    }

    @Override
    public boolean postSend(ChatMessage message, HandlerContext ctx, List<String> targets, List<String> successList) {
        return true;
    }
}
