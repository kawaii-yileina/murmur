package com.im.core.chat.handler;

import com.elaina.chat.core.handler.HandlerChain;
import com.elaina.chat.core.handler.MessageHandler;
import com.elaina.chat.core.message.ChatMessage;
import com.im.common.constant.ChatConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 身份绑定：强制客户端入站消息的 from = 该连接 JWT 认证出的用户，防冒充他人发言。
 * 框架仅在 from 为空时回填，若信任客户端传入的 from，攻击者可伪造他人身份（好友/群成员校验基于 from）。
 * 执行顺序由 ChatConfigurer.addHandlers 的注册顺序决定（须最先注册）。
 */
@Slf4j
@Component
public class IdentityBindingHandler implements MessageHandler {

    @Override
    public void handle(ChatMessage message, HandlerChain chain) {
        Object authed = chain.getContext().getAttributes().get(ChatConstants.ATTR_AUTHED_USER_ID);
        if (authed == null) {
            // 未认证的连接不应进入处理链：保守丢弃
            log.warn("Drop inbound message without authenticated identity: type={}", message.getType());
            return;
        }
        String userId = String.valueOf(authed);
        String claimed = message.getFrom();
        if (claimed != null && !claimed.isEmpty() && !claimed.equals(userId)) {
            // 伪造他人身份：断开连接，不投递、不落库
            log.warn("Reject forged from={} (authed={}), closing connection", claimed, userId);
            chain.getContext().close();
            return;
        }
        chain.doHandler(message.withFrom(userId));
    }
}
