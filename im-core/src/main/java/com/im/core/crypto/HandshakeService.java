package com.im.core.crypto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;

/**
 * 应用层握手：处理客户端 HELLO，派生会话密钥并生成 ACK；校验客户端 CONFIRM 完成密钥确认。
 * 见 docs/消息体加密设计.md §4。
 */
@Slf4j
@Service
public class HandshakeService {

    private final ServerKeyManager keyManager;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public HandshakeService(ServerKeyManager keyManager, ObjectMapper objectMapper) {
        this.keyManager = keyManager;
        this.objectMapper = objectMapper;
    }

    public record Hello(@JsonProperty("v") int v,
                        @JsonProperty("keyId") String keyId,
                        @JsonProperty("cNonce") String cNonce,
                        @JsonProperty("pub") String pub) {
    }

    public record Ack(@JsonProperty("v") int v,
                      @JsonProperty("keyId") String keyId,
                      @JsonProperty("cNonce") String cNonce,
                      @JsonProperty("sNonce") String sNonce,
                      @JsonProperty("pub") String pub) {
    }

    public record Outcome(String ackJson, CryptoConnection connection) {
    }

    /** 处理客户端 HELLO，返回 ACK（明文）与已登记会话密钥的连接态 */
    public Outcome start(String helloJson) {
        try {
            Hello hello = objectMapper.readValue(helloJson, Hello.class);
            if (hello.v() != CryptoConstants.VERSION) {
                throw new IllegalArgumentException("unsupported crypto version: " + hello.v());
            }
            if (!keyManager.hasKey(hello.keyId())) {
                throw new IllegalArgumentException("unknown keyId");
            }
            byte[] cNonce = CryptoFormat.unb64(hello.cNonce());
            if (cNonce.length != CryptoConstants.NONCE_PREFIX_LEN) {
                throw new IllegalArgumentException("invalid client nonce");
            }
            byte[] clientPubSpki = CryptoFormat.unb64(hello.pub());
            PublicKey clientPub = Ecdh.decodePublicKey(clientPubSpki);

            KeyPair ephemeral = Ecdh.generateKeyPair();
            byte[] dhEs = Ecdh.sharedSecret(keyManager.privateKey(hello.keyId()), clientPub);
            byte[] dhEe = Ecdh.sharedSecret(ephemeral.getPrivate(), clientPub);
            byte[] ikm = concat(dhEs, dhEe);

            byte[] sNonce = new byte[CryptoConstants.NONCE_PREFIX_LEN];
            random.nextBytes(sNonce);
            byte[] serverPubSpki = ephemeral.getPublic().getEncoded();

            byte[] transcript = CryptoFormat.transcript(
                    hello.v(), hello.keyId(), cNonce, sNonce, clientPubSpki, serverPubSpki);
            byte[] prk = Hkdf.extract(sNonce, ikm);
            byte[] c2sKey = CryptoFormat.deriveKey(prk, hello.keyId(), CryptoConstants.DIR_C2S, transcript);
            byte[] s2cKey = CryptoFormat.deriveKey(prk, hello.keyId(), CryptoConstants.DIR_S2C, transcript);

            CryptoConnection connection = new CryptoConnection();
            connection.applyHandshake(hello.keyId(), c2sKey, s2cKey, cNonce, sNonce, transcript,
                    ephemeral.getPrivate());

            Ack ack = new Ack(hello.v(), hello.keyId(), CryptoFormat.b64(cNonce),
                    CryptoFormat.b64(sNonce), CryptoFormat.b64(serverPubSpki));
            return new Outcome(objectMapper.writeValueAsString(ack), connection);
        } catch (Exception e) {
            throw new IllegalArgumentException("handshake failed: " + e.getMessage(), e);
        }
    }

    /** 生成服务端 CONFIRM 密文（固定 seq=0，AAD=transcript） */
    public byte[] buildConfirm(CryptoConnection connection) {
        if (connection.getState() != CryptoConnection.State.HANDSHAKING) {
            throw new IllegalStateException("not in HANDSHAKING state");
        }
        byte[] nonce = CryptoFormat.nonce(connection.getSNonce(), CryptoConstants.CONFIRM_SEQ);
        byte[] ciphertext = AesGcm.encrypt(connection.getS2cKey(), nonce, connection.getTranscript(), new byte[0]);
        connection.incSendSeq();
        return ciphertext;
    }

    /** 校验客户端 CONFIRM 密文（固定 seq=0）；通过则进入 ESTABLISHED */
    public boolean verifyConfirm(CryptoConnection connection, byte[] ciphertext) {
        if (connection.getState() != CryptoConnection.State.HANDSHAKING
                || connection.peekRecvSeq() != CryptoConstants.CONFIRM_SEQ) {
            return false;
        }
        try {
            byte[] nonce = CryptoFormat.nonce(connection.getCNonce(), CryptoConstants.CONFIRM_SEQ);
            AesGcm.decrypt(connection.getC2sKey(), nonce, connection.getTranscript(), ciphertext);
        } catch (Exception e) {
            return false;
        }
        connection.markEstablished();
        return true;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
