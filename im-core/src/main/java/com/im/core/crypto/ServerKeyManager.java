package com.im.core.crypto;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务端静态身份密钥管理：加载配置的 PKCS8 私钥（可多把，支持双 key 轮换），
 * 或在未配置时生成开发期临时密钥。私钥只存于内存，不落日志、不进代码库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServerKeyManager {

    private final CryptoProperties properties;
    private volatile Map<String, KeyMaterial> keyMap;
    private volatile String activeKeyId;

    private record KeyMaterial(PrivateKey privateKey, PublicKey publicKey) {
    }

    /** 启动即加载并校验密钥，坏配置快速失败，同时在日志中暴露实际生效的 keyId 集合 */
    @PostConstruct
    public void init() {
        ensureInit();
    }

    private void ensureInit() {
        if (keyMap != null) {
            return;
        }
        synchronized (this) {
            if (keyMap != null) {
                return;
            }
            Map<String, KeyMaterial> loaded = new LinkedHashMap<>();

            String legacyId = properties.getKeyId();
            String legacyPrivate = properties.getPrivateKey();
            if (legacyPrivate != null && !legacyPrivate.isBlank()) {
                validateKeyId(legacyId);
                loaded.put(legacyId, decode(legacyId, legacyPrivate, properties.getPublicKey()));
            }

            if (properties.getKeys() != null) {
                for (CryptoProperties.KeyEntry entry : properties.getKeys()) {
                    if (entry == null) {
                        continue;
                    }
                    boolean hasId = entry.getKeyId() != null && !entry.getKeyId().isBlank();
                    boolean hasPrivate = entry.getPrivateKey() != null && !entry.getPrivateKey().isBlank();
                    if (!hasId && !hasPrivate) {
                        continue;
                    }
                    if (!hasId || !hasPrivate) {
                        log.warn("im.crypto.keys 条目不完整，已忽略: keyId={}, privateKey={}",
                                hasId ? entry.getKeyId() : "<empty>", hasPrivate ? "set" : "<empty>");
                        continue;
                    }
                    validateKeyId(entry.getKeyId());
                    if (loaded.containsKey(entry.getKeyId())) {
                        throw new IllegalStateException("im.crypto.keys 存在重复 key-id: " + entry.getKeyId());
                    }
                    loaded.put(entry.getKeyId(),
                            decode(entry.getKeyId(), entry.getPrivateKey(), entry.getPublicKey()));
                }
            }

            if (loaded.isEmpty()) {
                String keyId = properties.getKeyId();
                validateKeyId(keyId);
                KeyPair pair = Ecdh.generateKeyPair();
                loaded.put(keyId, new KeyMaterial(pair.getPrivate(), pair.getPublic()));
                log.warn("im.crypto 未配置固定密钥，已生成临时服务端静态密钥（仅限开发；"
                        + "生产必须配置固定密钥，否则多实例/重启不一致）");
            }

            String preferred = properties.getKeyId();
            this.activeKeyId = loaded.containsKey(preferred) ? preferred : loaded.keySet().iterator().next();
            this.keyMap = Collections.unmodifiableMap(loaded);
            log.info("im.crypto 已加载服务端密钥: active={}, keyIds={}", activeKeyId, keyMap.keySet());
        }
    }

    private static KeyMaterial decode(String keyId, String privateKeyB64, String publicKeyB64) {
        try {
            KeyFactory factory = KeyFactory.getInstance("EC");
            PrivateKey privateKey = factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyB64)));
            if (privateKey instanceof ECPrivateKey ec
                    && ec.getParams().getCurve().getField().getFieldSize() != 256) {
                throw new IllegalStateException("服务端密钥曲线必须为 P-256（256-bit）: " + keyId);
            }
            PublicKey publicKey = null;
            if (publicKeyB64 != null && !publicKeyB64.isBlank()) {
                publicKey = factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)));
            }
            return new KeyMaterial(privateKey, publicKey);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to init server crypto key: " + keyId, e);
        }
    }

    private static void validateKeyId(String keyId) {
        if (keyId == null || !keyId.matches(CryptoConstants.KEY_ID_PATTERN)) {
            throw new IllegalStateException("im.crypto.key-id 非法（仅允许 [a-zA-Z0-9-]{1,32}）：" + keyId);
        }
    }

    public boolean hasKey(String keyId) {
        ensureInit();
        return keyId != null && keyMap.containsKey(keyId);
    }

    public PrivateKey privateKey(String keyId) {
        ensureInit();
        KeyMaterial material = keyMap.get(keyId);
        if (material == null) {
            throw new IllegalArgumentException("unknown keyId: " + keyId);
        }
        return material.privateKey();
    }

    /** 服务端静态公钥（SPKI DER），供客户端内置 pin */
    public byte[] publicKeySpki(String keyId) {
        ensureInit();
        KeyMaterial material = keyMap.get(keyId);
        if (material == null || material.publicKey() == null) {
            throw new IllegalStateException("公钥未配置：请为 keyId=" + keyId + " 设置 im.crypto.public-key");
        }
        return material.publicKey().getEncoded();
    }
}
