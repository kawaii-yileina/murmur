package com.im.core.chat.config;

import com.elaina.chat.core.chatConfig.ChatConfigurer;
import com.elaina.chat.core.registry.HandlerRegistry;
import com.elaina.chat.core.registry.InterceptorRegistry;
import com.im.core.chat.handler.HeartbeatHandler;
import com.im.core.chat.handler.MediaMessageHandler;
import com.im.core.chat.handler.TextMessageHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class ChatConfiguration implements ChatConfigurer {

    private final TextMessageHandler textMessageHandler;
    private final MediaMessageHandler mediaMessageHandler;
    private final HeartbeatHandler heartbeatHandler;

    @Override
    public void addHandlers(HandlerRegistry registry) {
        registry.addHandler(heartbeatHandler);
        registry.addHandler(textMessageHandler);
        registry.addHandler(mediaMessageHandler);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
    }
}
