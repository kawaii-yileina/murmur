package com.im.core.crypto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * 服务端静态身份密钥管理：加载配置的 PKCS8 私钥（推荐）或开发期生成临时密钥。
 * 私钥只存于内存并来自配置（环境变量/密钥库），不落日志、不进代码库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServerKeyManager {

    private final CryptoProperties properties;
    private volatile PrivateKey privateKey;
    private volatile PublicKey publicKey;

    private void ensureInit() {
        if (privateKey != null) {
            return;
        }
        synchronized (this) {
            if (privateKey != null) {
                return;
            }
            String keyId = properties.getKeyId();
            if (keyId == null || !keyId.matches(CryptoConstants.KEY_ID_PATTERN)) {
                throw new IllegalStateException("im.crypto.key-id 非法（仅允许 [a-zA-Z0-9-]{1,32}）：" + keyId);
            }
            String configured = properties.getPrivateKey();
            try {
                if (configured != null && !configured.isBlank()) {
                    KeyFactory factory = KeyFactory.getInstance("EC");
                    PrivateKey pk = factory.generatePrivate(
                            new PKCS8EncodedKeySpec(Base64.getDecoder().decode(configured)));
                    if (pk instanceof java.security.interfaces.ECPrivateKey ec
                            && ec.getParams().getCurve().getField().getFieldSize() != 256) {
                        throw new IllegalStateException("服务端密钥曲线必须为 P-256（256-bit）");
                    }
                    this.privateKey = pk;
                    String pub = properties.getPublicKey();
                    if (pub != null && !pub.isBlank()) {
                        this.publicKey = factory.generatePublic(
                                new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(pub)));
                    }
                } else {
                    KeyPair pair = Ecdh.generateKeyPair();
                    this.privateKey = pair.getPrivate();
                    this.publicKey = pair.getPublic();
                    log.warn("im.crypto.private-key 未配置，已生成临时服务端静态密钥（仅限开发；生产必须配置固定密钥，否则多实例/重启不一致）");
                }
            } catch (Exception e) {
                throw new IllegalStateException("Failed to init server crypto key: " + e, e);
            }
        }
    }

    public boolean hasKey(String keyId) {
        ensureInit();
        return keyId != null && keyId.equals(properties.getKeyId());
    }

    public String keyId() {
        ensureInit();
        return properties.getKeyId();
    }

    public PrivateKey privateKey(String keyId) {
        ensureInit();
        if (keyId == null || !keyId.equals(properties.getKeyId())) {
            throw new IllegalArgumentException("unknown keyId: " + keyId);
        }
        return privateKey;
    }

    /** 服务端静态公钥（SPKI DER），供客户端内置 pin */
    public byte[] publicKeySpki(String keyId) {
        ensureInit();
        if (publicKey == null) {
            throw new IllegalStateException("公钥未配置：请同时设置 im.crypto.public-key 以导出给客户端");
        }
        return publicKey.getEncoded();
    }
}
