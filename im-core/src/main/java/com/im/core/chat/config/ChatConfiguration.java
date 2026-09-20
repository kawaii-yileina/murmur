package com.im.core.chat.config;

import com.elaina.chat.core.chatConfig.ChatConfigurer;
import com.elaina.chat.core.registry.HandlerRegistry;
import com.elaina.chat.core.registry.InterceptorRegistry;
import com.elaina.chat.core.registry.UserConnectionRegistry;
import com.im.core.chat.handler.HeartbeatHandler;
import com.im.core.chat.handler.MediaMessageHandler;
import com.im.core.chat.handler.MessageIdAssignHandler;
import com.im.core.chat.handler.TextMessageHandler;
import com.im.core.chat.interceptor.RateLimitInterceptor;
import com.im.core.chat.interceptor.TypingInterceptor;
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
    private final UserConnectionRegistry connectionRegistry;


    @Override
    public void addHandlers(HandlerRegistry registry) {
        registry.addHandler(messageIdAssignHandler);
        registry.addHandler(heartbeatHandler);
        registry.addHandler(textMessageHandler);
        registry.addHandler(mediaMessageHandler);
    }

    /**
     * 拦截器在这里手动 new 并注册，不能声明为 @Component。
     * 原因：框架的 MessageDispatcher 通过 List&lt;MessageInterceptor&gt; 注入拦截器链，
     * Spring 会优先收集"本身就是 MessageInterceptor 的 Bean"；一旦本应用存在这样的 Bean，
     * 框架 ChatConfigurationSupport.buildInterceptors() 返回的列表（含负责落库与回 ACK 的
     * MessagePersistenceInterceptor）就会被顶掉，表现为消息不落库、不回 ACK。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TypingInterceptor(connectionRegistry));
    }
}
