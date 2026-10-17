package com.im.core.crypto;

import com.elaina.chat.core.message.ChatMessage;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 连接级加解密 handler（Netty duplex）：置于 ChatMessageDecoder/Encoder 与业务 handler 之间。
 * - 入向：握手帧处理（HELLO/ACK/CONFIRM），业务帧解密后交给业务链（fail-closed）。
 * - 出向：业务帧加密；握手帧直通；握手完成前的出向进入有界缓冲，绝不明文下线。
 * 仅在 im.crypto.enabled=true 时生效。
 */
@Slf4j
public class CryptoDuplexHandler extends ChannelDuplexHandler {

    private static final AttributeKey<CryptoConnection> CRYPTO_KEY = AttributeKey.valueOf("cryptoConn");

    private final CryptoProperties properties;
    private final HandshakeService handshakeService;
    private final CryptoPayloadCodec payloadCodec;
    private final String keyId;

    /** 握手完成前的出向缓冲（本 handler 每连接一个实例，仅在 EventLoop 线程访问） */
    private final Deque<ChatMessage> pending = new ArrayDeque<>();

    public CryptoDuplexHandler(CryptoProperties properties, HandshakeService handshakeService,
                               CryptoPayloadCodec payloadCodec) {
        this.properties = properties;
        this.handshakeService = handshakeService;
        this.payloadCodec = payloadCodec;
        this.keyId = properties.getKeyId();
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        ctx.channel().attr(CRYPTO_KEY).set(new CryptoConnection());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        CryptoConnection conn = ctx.channel().attr(CRYPTO_KEY).get();
        if (conn != null) {
            conn.clear();
        }
        pending.clear();
        ctx.fireChannelInactive();
    }

    private CryptoConnection connection(ChannelHandlerContext ctx) {
        CryptoConnection conn = ctx.channel().attr(CRYPTO_KEY).get();
        if (conn == null) {
            conn = new CryptoConnection();
            ctx.channel().attr(CRYPTO_KEY).set(conn);
        }
        return conn;
    }

    // ---------------- 入向 ----------------

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!properties.isEnabled() || !(msg instanceof ChatMessage m)) {
            ctx.fireChannelRead(msg);
            return;
        }
        String type = m.getType();
        try {
            if (CryptoConstants.FRAME_HELLO.equals(type)) {
                handleHello(ctx, m);
            } else if (CryptoConstants.FRAME_CONFIRM.equals(type)) {
                handleConfirm(ctx, m);
            } else if (CryptoConstants.FRAME_HELLO_ACK.equals(type)) {
                // 客户端不应上行服务端帧
                closeQuietly(ctx);
            } else {
                handleBusiness(ctx, m);
            }
        } catch (Exception e) {
            log.warn("crypto inbound failed: type={}, err={}", type, e.getMessage());
            closeQuietly(ctx);
        }
    }

    private void handleHello(ChannelHandlerContext ctx, ChatMessage m) {
        CryptoConnection conn = connection(ctx);
        if (conn.getState() != CryptoConnection.State.AUTHED) {
            closeQuietly(ctx);
            return;
        }
        String helloJson = m.getContent();
        if (helloJson == null || helloJson.isEmpty() || helloJson.length() > 4096) {
            closeQuietly(ctx);
            return;
        }
        HandshakeService.Outcome outcome = handshakeService.start(helloJson);
        CryptoConnection hs = outcome.connection();
        ctx.channel().attr(CRYPTO_KEY).set(hs);

        ctx.writeAndFlush(ChatMessage.builder().type(CryptoConstants.FRAME_HELLO_ACK)
                .content(outcome.ackJson()).build());
        byte[] confirm = handshakeService.buildConfirm(hs);
        ctx.writeAndFlush(ChatMessage.builder().type(CryptoConstants.FRAME_CONFIRM)
                .content(CryptoFormat.b64(confirm)).build());
    }

    private void handleConfirm(ChannelHandlerContext ctx, ChatMessage m) {
        CryptoConnection conn = connection(ctx);
        if (conn.getState() != CryptoConnection.State.HANDSHAKING || m.getContent() == null) {
            closeQuietly(ctx);
            return;
        }
        boolean ok;
        try {
            ok = handshakeService.verifyConfirm(conn, CryptoFormat.unb64(m.getContent()));
        } catch (Exception e) {
            ok = false;
        }
        if (!ok) {
            closeQuietly(ctx);
            return;
        }
        flushPending(ctx, conn);
    }

    private void handleBusiness(ChannelHandlerContext ctx, ChatMessage m) {
        CryptoConnection conn = connection(ctx);
        if (conn.getState() != CryptoConnection.State.ESTABLISHED) {
            // fail-closed：未完成握手前不接受业务帧
            closeQuietly(ctx);
            return;
        }
        if (m.getHeader(CryptoConstants.HEADER_ENCRYPTED) == null) {
            // 已建立加密会话，拒绝明文业务帧
            closeQuietly(ctx);
            return;
        }
        int v = parseInt(m.getHeader(CryptoConstants.HEADER_CRYPTO_V));
        String ckid = str(m.getHeader(CryptoConstants.HEADER_CRYPTO_KEY_ID));
        long seq = parseLong(m.getHeader(CryptoConstants.HEADER_CRYPTO_SEQ));
        byte[] iv = CryptoFormat.unb64(str(m.getHeader(CryptoConstants.HEADER_CRYPTO_IV)));
        if (v != CryptoConstants.VERSION || !keyId.equals(ckid) || iv.length != CryptoConstants.NONCE_LEN
                || seq != conn.peekRecvSeq()) {
            closeQuietly(ctx);
            return;
        }
        byte[] aad = CryptoFormat.aad(v, ckid, CryptoConstants.DIR_C2S, seq,
                m.getType(), m.getId(), m.getFrom(), m.getTo(), m.isGroup(), m.getTimestamp());
        byte[] plaintext = AesGcm.decrypt(conn.getC2sKey(), iv, aad, CryptoFormat.unb64(m.getContent()));
        CryptoPayloadCodec.Decoded decoded = payloadCodec.decode(plaintext);
        conn.incRecvSeq();

        ChatMessage restored = m.copy("content", "headers")
                .withContent(decoded.content())
                .withHeaders(decoded.headers());
        ctx.fireChannelRead(restored);
    }

    // ---------------- 出向 ----------------

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (!properties.isEnabled() || !(msg instanceof ChatMessage m)) {
            ctx.write(msg, promise);
            return;
        }
        String type = m.getType();
        if (isHandshakeFrame(type)) {
            ctx.write(msg, promise);
            return;
        }
        CryptoConnection conn = connection(ctx);
        if (conn.getState() != CryptoConnection.State.ESTABLISHED) {
            // 握手完成前缓冲，绝不明文下发
            if (pending.size() >= CryptoConstants.PRE_HANDSHAKE_BUFFER_LIMIT) {
                log.warn("pre-handshake buffer full, drop message: type={}", type);
            } else {
                pending.addLast(m);
            }
            promise.setSuccess();
            return;
        }
        try {
            ctx.write(encrypt(conn, m), promise);
        } catch (Exception e) {
            log.warn("crypto outbound failed, closing: {}", e.getMessage());
            promise.tryFailure(e);
            ctx.close();
        }
    }

    private void flushPending(ChannelHandlerContext ctx, CryptoConnection conn) {
        ChatMessage m;
        while ((m = pending.pollFirst()) != null) {
            try {
                ctx.writeAndFlush(encrypt(conn, m));
            } catch (Exception e) {
                log.warn("flush pending encrypt failed, closing: {}", e.getMessage());
                ctx.close();
                return;
            }
        }
    }

    private ChatMessage encrypt(CryptoConnection conn, ChatMessage m) {
        long seq = conn.peekSendSeq();
        byte[] iv = CryptoFormat.nonce(conn.getSNonce(), seq);
        byte[] aad = CryptoFormat.aad(CryptoConstants.VERSION, keyId, CryptoConstants.DIR_S2C, seq,
                m.getType(), m.getId(), m.getFrom(), m.getTo(), m.isGroup(), m.getTimestamp());
        byte[] plaintext = payloadCodec.encode(m.getContent(), m.getHeaders());
        byte[] ciphertext = AesGcm.encrypt(conn.getS2cKey(), iv, aad, plaintext);
        conn.incSendSeq();

        Map<String, Object> clear = new HashMap<>();
        clear.put(CryptoConstants.HEADER_ENCRYPTED, "1");
        clear.put(CryptoConstants.HEADER_CRYPTO_V, String.valueOf(CryptoConstants.VERSION));
        clear.put(CryptoConstants.HEADER_CRYPTO_KEY_ID, keyId);
        clear.put(CryptoConstants.HEADER_CRYPTO_IV, CryptoFormat.b64(iv));
        clear.put(CryptoConstants.HEADER_CRYPTO_SEQ, String.valueOf(seq));
        return m.copy("content", "headers").withContent(CryptoFormat.b64(ciphertext)).withHeaders(clear);
    }

    private static boolean isHandshakeFrame(String type) {
        return CryptoConstants.FRAME_HELLO.equals(type)
                || CryptoConstants.FRAME_HELLO_ACK.equals(type)
                || CryptoConstants.FRAME_CONFIRM.equals(type);
    }

    private void closeQuietly(ChannelHandlerContext ctx) {
        ctx.close();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int parseInt(Object o) {
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long parseLong(Object o) {
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
