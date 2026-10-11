package com.im.core.chat.config;

import com.elaina.chat.core.chatConfig.ChatConfigurer;
import com.elaina.chat.core.registry.HandlerRegistry;
import com.elaina.chat.core.registry.InterceptorRegistry;
import com.im.core.chat.registry.MultiConnectionRegistry;
import com.im.core.chat.handler.HeartbeatHandler;
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

@Configuration
@RequiredArgsConstructor
public class ChatConfiguration implements ChatConfigurer {

    private final MessageIdAssignHandler messageIdAssignHandler;
    private final TextMessageHandler textMessageHandler;
    private final MediaMessageHandler mediaMessageHandler;
    private final HeartbeatHandler heartbeatHandler;
    private final StringRedisTemplate redisTemplate;
    private final MultiConnectionRegistry connectionRegistry;
    private final CallService callService;
    private final CallSignalRouter callSignalRouter;


    @Override
    public void addHandlers(HandlerRegistry registry) {
        registry.addHandler(messageIdAssignHandler);
        registry.addHandler(heartbeatHandler);
        registry.addHandler(textMessageHandler);
        registry.addHandler(mediaMessageHandler);
    }


    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TypingInterceptor(connectionRegistry));
        registry.addInterceptor(new CallSignalingInterceptor(callService, callSignalRouter));
    }
}
