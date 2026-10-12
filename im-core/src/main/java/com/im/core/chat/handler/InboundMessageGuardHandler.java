package com.im.core.chat.handler;

import com.elaina.chat.core.handler.HandlerChain;
import com.elaina.chat.core.handler.MessageHandler;
import com.elaina.chat.core.message.ChatMessage;
import com.im.common.constant.ChatConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 入站消息守卫：拒绝客户端伪造的服务端专用消息类型（如通话记录 CALL）。
 *
 * 服务端生成的通话记录经 MessageGateway 直接下发，不经过处理器链（HandlerChain 仅处理客户端入站消息），
 * 故此处只拦截伪造上行，不影响合法的通话记录下发与落库。终止链即不投递、不落库。
 */
@Slf4j
@Component
public class InboundMessageGuardHandler implements MessageHandler {

    @Override
    public void handle(ChatMessage message, HandlerChain chain) {
        if (ChatConstants.MSG_TYPE_CALL.equals(message.getType())) {
            log.warn("Reject inbound reserved message type: type={}, from={}", message.getType(), message.getFrom());
            return;
        }
        chain.doHandler(message);
    }
}
