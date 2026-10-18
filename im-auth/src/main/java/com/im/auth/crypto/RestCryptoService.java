package com.im.auth.crypto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.core.crypto.AesGcm;
import com.im.core.crypto.CryptoConstants;
import com.im.core.crypto.CryptoFormat;
import com.im.core.crypto.CryptoProperties;
import com.im.core.crypto.Ecdh;
import com.im.core.crypto.Hkdf;
import com.im.core.crypto.ServerKeyManager;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;

/**
 * REST 响应加密（im-rest-v1）：每请求临时密钥 ECIES，无服务端会话状态。
 * 协议见 docs/消息体加密设计.md §16。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestCryptoService {

    public static final String HEADER_EPH = "X-Crypto-Eph";
    public static final String HEADER_NONCE = "X-Crypto-Nonce";
    public static final String HEADER_KEY_ID = "X-Crypto-Key-Id";
    public static final String HEADER_CTX = "X-Crypto-Ctx";

    private static final int NONCE_LEN = 4;
    private static final int MAX_EPH_BYTES = 256;
    private static final String CTX_PATTERN = "[0-9a-f]{64}";

    private final CryptoProperties properties;
    private final ServerKeyManager keyManager;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 校验请求头并派生本次响应的密钥。任何格式/上下文/密钥问题都抛 RestCryptoException。
     */
    public RestCryptoContext begin(HttpServletRequest request) {
        String ephB64 = requireHeader(request, HEADER_EPH);
        String nonceB64 = requireHeader(request, HEADER_NONCE);
        String keyId = requireHeader(request, HEADER_KEY_ID);
        String ctxHex = requireHeader(request, HEADER_CTX);

        if (!keyId.matches(CryptoConstants.KEY_ID_PATTERN)) {
            throw new RestCryptoException("X-Crypto-Key-Id 非法");
        }
        if (!ctxHex.matches(CTX_PATTERN)) {
            throw new RestCryptoException("X-Crypto-Ctx 非法");
        }
        if (nonceB64.length() > 32) {
            throw new RestCryptoException("X-Crypto-Nonce 过长");
        }

        byte[] cNonce = decodeBase64(nonceB64, HEADER_NONCE);
        if (cNonce.length != NONCE_LEN) {
            throw new RestCryptoException("X-Crypto-Nonce 长度必须为 4 字节");
        }

        if (ephB64.length() > 512) {
            throw new RestCryptoException("X-Crypto-Eph 过长");
        }
        byte[] clientPubBytes = decodeBase64(ephB64, HEADER_EPH);
        if (clientPubBytes.length > MAX_EPH_BYTES) {
            throw new RestCryptoException("X-Crypto-Eph 过长");
        }
        PublicKey clientPub;
        try {
            clientPub = Ecdh.decodePublicKey(clientPubBytes);
        } catch (IllegalArgumentException e) {
            throw new RestCryptoException("X-Crypto-Eph 不是合法的 P-256 公钥");
        }
        if (!(clientPub instanceof ECPublicKey ec)
                || ec.getParams().getCurve().getField().getFieldSize() != 256) {
            throw new RestCryptoException("X-Crypto-Eph 必须是 P-256 公钥");
        }

        if (!keyManager.hasKey(keyId)) {
            throw new RestCryptoException("未知的 X-Crypto-Key-Id");
        }

        String expectedCtx = CryptoFormat.hex(CryptoFormat.sha256(
                (request.getMethod() + " " + request.getRequestURI()).getBytes(StandardCharsets.UTF_8)));
        if (!MessageDigest.isEqual(expectedCtx.getBytes(StandardCharsets.US_ASCII),
                ctxHex.getBytes(StandardCharsets.US_ASCII))) {
            throw new RestCryptoException("X-Crypto-Ctx 与请求不匹配");
        }

        KeyPair serverEphemeral = Ecdh.generateKeyPair();
        byte[] serverPub = Ecdh.encodePublicKey(serverEphemeral.getPublic());
        byte[] sNonce = new byte[NONCE_LEN];
        secureRandom.nextBytes(sNonce);

        byte[] dhEs;
        byte[] dhEe;
        try {
            dhEs = Ecdh.sharedSecret(keyManager.privateKey(keyId), clientPub);
            dhEe = Ecdh.sharedSecret(serverEphemeral.getPrivate(), clientPub);
        } catch (RuntimeException e) {
            log.debug("REST crypto ECDH failed: keyId={}, path={}", keyId, request.getRequestURI());
            throw new RestCryptoException("X-Crypto-Eph 协商失败");
        }
        byte[] ikm = new byte[dhEs.length + dhEe.length];
        System.arraycopy(dhEs, 0, ikm, 0, dhEs.length);
        System.arraycopy(dhEe, 0, ikm, dhEs.length, dhEe.length);

        byte[] prk = Hkdf.extract(sNonce, ikm);
        byte[] transcript = CryptoFormat.restTranscript(
                CryptoConstants.VERSION, keyId, ctxHex, cNonce, sNonce, clientPubBytes, serverPub);
        byte[] key = CryptoFormat.deriveRestKey(prk, keyId, transcript);
        byte[] iv = CryptoFormat.nonce(sNonce, 0);

        log.debug("REST crypto negotiated: keyId={}, path={}", keyId, request.getRequestURI());
        return new RestCryptoContext(keyId, sNonce, serverPub, transcript, key, iv);
    }

    /** 加密 Result.data 的 JSON 序列化结果；不记录明文。 */
    public EncryptedPayload encrypt(Object data, RestCryptoContext context) {
        if (context == null) {
            throw new IllegalStateException("REST 加密上下文缺失");
        }
        try {
            byte[] plaintext = objectMapper.writeValueAsBytes(data);
            byte[] ciphertext = AesGcm.encrypt(context.key(), context.iv(), context.transcript(), plaintext);
            log.debug("REST response encrypted: keyId={}, bytes={}", context.keyId(), plaintext.length);
            return new EncryptedPayload(
                    CryptoConstants.VERSION,
                    context.keyId(),
                    CryptoFormat.b64(context.sNonce()),
                    CryptoFormat.b64(context.serverPub()),
                    CryptoFormat.b64(context.iv()),
                    CryptoFormat.b64(ciphertext));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("REST 响应序列化失败", e);
        }
    }

    private static String requireHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            throw new RestCryptoException("缺少 " + name);
        }
        return value.trim();
    }

    private static byte[] decodeBase64(String value, String name) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new RestCryptoException(name + " 不是合法 Base64");
        }
    }
}
