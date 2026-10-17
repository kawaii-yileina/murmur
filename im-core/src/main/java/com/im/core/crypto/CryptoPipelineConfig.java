package com.im.core.crypto;

import com.elaina.chat.core.codec.ChatCodec;
import com.elaina.chat.core.connection.ConnectionAttributeInitializer;
import com.elaina.chat.transport.pipeline.ChannelPipelineCustomizer;
import com.elaina.chat.transport.property.Properties;
import com.elaina.chat.transport.server.ChatServerHandlerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 用带加解密 handler 的 pipeline 定制器替换框架默认实现（默认 Bean 为 @ConditionalOnMissingBean）。
 */
@Configuration
public class CryptoPipelineConfig {

    @Bean
    public ChannelPipelineCustomizer cryptoWebSocketPipelineCustomizer(
            ChatCodec codec,
            ChatServerHandlerFactory handlerFactory,
            List<ConnectionAttributeInitializer> attributeInitializers,
            Properties chatProperties,
            HandshakeService handshakeService,
            CryptoPayloadCodec payloadCodec,
            CryptoProperties cryptoProperties) {
        return new CryptoWebSocketPipelineCustomizer(codec, handlerFactory, attributeInitializers,
                chatProperties, handshakeService, payloadCodec, cryptoProperties);
    }
}
