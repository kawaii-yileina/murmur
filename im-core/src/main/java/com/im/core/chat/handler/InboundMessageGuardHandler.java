package com.im.core.chat.handler;

import com.elaina.chat.core.handler.HandlerChain;
import com.elaina.chat.core.handler.MessageHandler;
import com.elaina.chat.core.message.ChatMessage;
import com.im.common.constant.ChatConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 入站消息守卫：拒绝客户端伪造的服务端专用消息类型（如通话记录 CALL、SYSTEM 事件、ACK 回执）。
 *
 * 服务端生成的消息经 MessageGateway 直接下发，不经过处理器链（HandlerChain 仅处理客户端入站消息），
 * 故此处只拦截伪造上行，不影响合法的服务端下发与落库。终止链即不投递、不落库。
 */
@Slf4j
@Component
public class InboundMessageGuardHandler implements MessageHandler {

    /** 服务端专用类型白名单：客户端上行的这些类型一律丢弃 */
    private static final Set<String> RESERVED_TYPES = Set.of(
            ChatConstants.MSG_TYPE_CALL, ChatConstants.MSG_TYPE_SYSTEM, ChatConstants.MSG_TYPE_ACK);

    @Override
    public void handle(ChatMessage message, HandlerChain chain) {
        if (RESERVED_TYPES.contains(message.getType())) {
            log.warn("Reject inbound reserved message type: type={}, from={}", message.getType(), message.getFrom());
            return;
        }
        chain.doHandler(message);
    }
}
