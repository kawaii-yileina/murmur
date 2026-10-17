package com.im.core.crypto;

import com.elaina.chat.core.codec.ChatCodec;
import com.elaina.chat.core.connection.ConnectionAttributeInitializer;
import com.elaina.chat.transport.pipeline.AbstractWebSocketPipelineCustomizer;
import com.elaina.chat.transport.property.Properties;
import com.elaina.chat.transport.server.ChatServerHandlerFactory;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;

import java.util.List;

/**
 * 在框架默认 pipeline 的 codec 与业务 handler 之间插入连接级加解密 handler。
 */
public class CryptoWebSocketPipelineCustomizer extends AbstractWebSocketPipelineCustomizer {

    private final HandshakeService handshakeService;
    private final CryptoPayloadCodec payloadCodec;
    private final CryptoProperties cryptoProperties;

    public CryptoWebSocketPipelineCustomizer(ChatCodec codec,
                                             ChatServerHandlerFactory handlerFactory,
                                             List<ConnectionAttributeInitializer> attributeInitializers,
                                             Properties chatProperties,
                                             HandshakeService handshakeService,
                                             CryptoPayloadCodec payloadCodec,
                                             CryptoProperties cryptoProperties) {
        super(codec, handlerFactory, attributeInitializers, chatProperties);
        this.handshakeService = handshakeService;
        this.payloadCodec = payloadCodec;
        this.cryptoProperties = cryptoProperties;
    }


    @Override
    protected void addCustomBusinessHandler(ChannelPipeline pipeline) {
        super.addCustomBusinessHandler(pipeline);
        pipeline.addLast(new CryptoDuplexHandler(cryptoProperties, handshakeService, payloadCodec));
    }
}
