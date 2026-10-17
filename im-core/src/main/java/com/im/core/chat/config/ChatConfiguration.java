package com.im.core.chat.config;

import com.elaina.chat.core.chatConfig.ChatConfigurer;
import com.elaina.chat.core.handler.HandlerContext;
import com.elaina.chat.core.handler.MessageInterceptor;
import com.elaina.chat.core.message.ChatMessage;
import com.elaina.chat.core.registry.HandlerRegistry;
import com.elaina.chat.core.registry.InterceptorRegistry;
import com.im.common.constant.ChatConstants;
import com.im.core.chat.registry.MultiConnectionRegistry;
import com.im.core.chat.handler.HeartbeatHandler;
import com.im.core.chat.handler.IdentityBindingHandler;
import com.im.core.chat.handler.InboundMessageGuardHandler;
import com.im.core.chat.handler.MediaMessageHandler;
import com.im.core.chat.handler.MessageIdAssignHandler;
import com.im.core.chat.handler.TextMessageHandler;
import com.im.core.chat.interceptor.CallSignalingInterceptor;
import com.im.core.chat.interceptor.RateLimitInterceptor;
import com.im.core.chat.interceptor.TypingInterceptor;
import com.im.core.call.CallService;
import com.im.core.call.CallSignalRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class ChatConfiguration implements ChatConfigurer {

    private final MessageIdAssignHandler messageIdAssignHandler;
    private final TextMessageHandler textMessageHandler;
    private final MediaMessageHandler mediaMessageHandler;
    private final HeartbeatHandler heartbeatHandler;
    private final InboundMessageGuardHandler inboundMessageGuardHandler;
    private final IdentityBindingHandler identityBindingHandler;
    private final StringRedisTemplate redisTemplate;
    private final MultiConnectionRegistry connectionRegistry;
    private final CallService callService;
    private final CallSignalRouter callSignalRouter;

    @Value("${im.call.enabled:true}")
    private boolean callEnabled;


    @Override
    public void addHandlers(HandlerRegistry registry) {
        // 身份绑定必须最先注册：在路由/校验（基于 from）之前强制 from = 认证身份
        registry.addHandler(identityBindingHandler);
        registry.addHandler(inboundMessageGuardHandler);
        registry.addHandler(messageIdAssignHandler);
        registry.addHandler(heartbeatHandler);
        registry.addHandler(textMessageHandler);
        registry.addHandler(mediaMessageHandler);
    }


    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TypingInterceptor(connectionRegistry));
        if (callEnabled) {
            registry.addInterceptor(new CallSignalingInterceptor(callService, callSignalRouter));
        } else {
            // 通话功能关闭：显式丢弃所有通话信令，避免其掉入普通消息链路被投递/落库
            registry.addInterceptor(new MessageInterceptor() {
                @Override
                public boolean preSend(ChatMessage message, HandlerContext ctx, List<String> targets) {
                    return !ChatConstants.isCallSignal(message.getType());
                }

                @Override
                public boolean postSend(ChatMessage message, HandlerContext ctx, List<String> targets,
                                        List<String> successList) {
                    return true;
                }
            });
        }
    }
}
