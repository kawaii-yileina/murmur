package com.im.crypto;

import com.elaina.chat.core.message.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.core.crypto.AesGcm;
import com.im.core.crypto.CryptoConstants;
import com.im.core.crypto.CryptoDuplexHandler;
import com.im.core.crypto.CryptoFormat;
import com.im.core.crypto.CryptoPayloadCodec;
import com.im.core.crypto.CryptoProperties;
import com.im.core.crypto.Ecdh;
import com.im.core.crypto.HandshakeService;
import com.im.core.crypto.Hkdf;
import com.im.core.crypto.ServerKeyManager;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CryptoDuplexHandler 失败路径单测（EmbeddedChannel）：握手、业务解密、篡改/序号/明文拒绝。
 */
class CryptoDuplexHandlerTest {

    private static final String KEY_ID = "k1";

    private static final class Ctx {
        EmbeddedChannel channel;
        byte[] cNonce;
        byte[] sNonce;
        byte[] transcript;
        byte[] c2sKey;
        byte[] s2cKey;
        String userId = "u1";
        String peerId = "u2";
    }

    private static ChatMessage frame(String type, String content) {
        return ChatMessage.builder().type(type).content(content).build();
    }

    /** 建立连接并完成握手，返回客户端侧密钥上下文 */
    private static Ctx establish() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CryptoProperties props = new CryptoProperties();
        props.setEnabled(true);
        props.setKeyId(KEY_ID);
        ServerKeyManager keyManager = new ServerKeyManager(props);
        CryptoPayloadCodec payloadCodec = new CryptoPayloadCodec(mapper);
        HandshakeService handshakeService = new HandshakeService(keyManager, mapper);

        EmbeddedChannel channel = new EmbeddedChannel(
                new CryptoDuplexHandler(props, handshakeService, payloadCodec));

        KeyPair clientEphemeral = Ecdh.generateKeyPair();
        byte[] cNonce = new byte[CryptoConstants.NONCE_PREFIX_LEN];
        new SecureRandom().nextBytes(cNonce);
        byte[] clientPub = clientEphemeral.getPublic().getEncoded();

        String hello = mapper.writeValueAsString(Map.of(
                "v", CryptoConstants.VERSION,
                "keyId", KEY_ID,
                "cNonce", CryptoFormat.b64(cNonce),
                "pub", CryptoFormat.b64(clientPub)));
        channel.writeInbound(frame(CryptoConstants.FRAME_HELLO, hello));

        ChatMessage ackMsg = channel.readOutbound();
        ChatMessage confirmMsg = channel.readOutbound();
        assertThat(ackMsg).isNotNull();
        assertThat(ackMsg.getType()).isEqualTo(CryptoConstants.FRAME_HELLO_ACK);
        assertThat(confirmMsg).isNotNull();
        assertThat(confirmMsg.getType()).isEqualTo(CryptoConstants.FRAME_CONFIRM);

        Map<?, ?> ack = mapper.readValue(ackMsg.getContent(), Map.class);
        byte[] sNonce = CryptoFormat.unb64((String) ack.get("sNonce"));
        byte[] serverPub = CryptoFormat.unb64((String) ack.get("pub"));
        byte[] serverStaticPub = keyManager.publicKeySpki(KEY_ID);

        byte[] dhEs = Ecdh.sharedSecret(clientEphemeral.getPrivate(), Ecdh.decodePublicKey(serverStaticPub));
        byte[] dhEe = Ecdh.sharedSecret(clientEphemeral.getPrivate(), Ecdh.decodePublicKey(serverPub));
        byte[] ikm = new byte[dhEs.length + dhEe.length];
        System.arraycopy(dhEs, 0, ikm, 0, dhEs.length);
        System.arraycopy(dhEe, 0, ikm, dhEs.length, dhEe.length);

        byte[] transcript = CryptoFormat.transcript(CryptoConstants.VERSION, KEY_ID, cNonce, sNonce,
                clientPub, serverPub);
        byte[] prk = Hkdf.extract(sNonce, ikm);
        byte[] c2sKey = CryptoFormat.deriveKey(prk, KEY_ID, CryptoConstants.DIR_C2S, transcript);
        byte[] s2cKey = CryptoFormat.deriveKey(prk, KEY_ID, CryptoConstants.DIR_S2C, transcript);

        byte[] confirmCt = CryptoFormat.unb64(confirmMsg.getContent());
        AesGcm.decrypt(s2cKey, CryptoFormat.nonce(sNonce, 0), transcript, confirmCt);

        byte[] clientConfirm = AesGcm.encrypt(c2sKey, CryptoFormat.nonce(cNonce, 0), transcript, new byte[0]);
        channel.writeInbound(frame(CryptoConstants.FRAME_CONFIRM, CryptoFormat.b64(clientConfirm)));

        Ctx ctx = new Ctx();
        ctx.channel = channel;
        ctx.cNonce = cNonce;
        ctx.sNonce = sNonce;
        ctx.transcript = transcript;
        ctx.c2sKey = c2sKey;
        ctx.s2cKey = s2cKey;
        return ctx;
    }

    private static ChatMessage encryptedText(Ctx ctx, long seq, String content, boolean tamper) throws Exception {
        ChatMessage base = ChatMessage.builder()
                .type("TEXT").from(ctx.userId).to(ctx.peerId).group(false).content("").build();
        long ts = base.getTimestamp();
        byte[] iv = CryptoFormat.nonce(ctx.cNonce, seq);
        byte[] aad = CryptoFormat.aad(CryptoConstants.VERSION, KEY_ID, CryptoConstants.DIR_C2S, seq,
                "TEXT", base.getId(), ctx.userId, ctx.peerId, false, ts);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        payload.put("headers", Map.of("k", "v"));
        byte[] pt = new ObjectMapper().writeValueAsBytes(payload);
        byte[] ct = AesGcm.encrypt(ctx.c2sKey, iv, aad, pt);
        if (tamper) {
            ct[0] ^= 0x01;
        }
        Map<String, Object> clear = new LinkedHashMap<>();
        clear.put(CryptoConstants.HEADER_ENCRYPTED, "1");
        clear.put(CryptoConstants.HEADER_CRYPTO_V, String.valueOf(CryptoConstants.VERSION));
        clear.put(CryptoConstants.HEADER_CRYPTO_KEY_ID, KEY_ID);
        clear.put(CryptoConstants.HEADER_CRYPTO_IV, CryptoFormat.b64(iv));
        clear.put(CryptoConstants.HEADER_CRYPTO_SEQ, String.valueOf(seq));
        return base.withContent(CryptoFormat.b64(ct)).withHeaders(clear);
    }

    @Test
    void decryptsValidBusinessFrame() throws Exception {
        Ctx ctx = establish();
        ctx.channel.writeInbound(encryptedText(ctx, 1, "hello-secure", false));
        ChatMessage restored = ctx.channel.readInbound();
        assertThat(restored).isNotNull();
        assertThat(restored.getContent()).isEqualTo("hello-secure");
        assertThat(restored.getHeaders().get("k")).isEqualTo("v");
        assertThat(restored.getFrom()).isEqualTo(ctx.userId);
        assertTrue(ctx.channel.isActive());
    }

    @Test
    void rejectsTamperedCiphertext() throws Exception {
        Ctx ctx = establish();
        ctx.channel.writeInbound(encryptedText(ctx, 1, "hello-secure", true));
        assertThat((Object) ctx.channel.readInbound()).isNull();
        assertFalse(ctx.channel.isActive());
    }

    @Test
    void rejectsOutOfOrderSeq() throws Exception {
        Ctx ctx = establish();
        // 跳过 seq=1 直接发 seq=2
        ctx.channel.writeInbound(encryptedText(ctx, 2, "skip-one", false));
        assertThat((Object) ctx.channel.readInbound()).isNull();
        assertFalse(ctx.channel.isActive());
    }

    @Test
    void rejectsPlaintextBusinessFrame() throws Exception {
        Ctx ctx = establish();
        ctx.channel.writeInbound(ChatMessage.builder()
                .type("TEXT").from(ctx.userId).to(ctx.peerId).group(false).content("plaintext").build());
        assertThat((Object) ctx.channel.readInbound()).isNull();
        assertFalse(ctx.channel.isActive());
    }
}
