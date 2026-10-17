package com.im.core.crypto;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * P-256 (secp256r1) ECDH：握手期协商共享密钥。
 * 公钥以 SubjectPublicKeyInfo (SPKI) DER 编码在线上传输（与 Web Crypto 的 'spki' 互通）。
 */
public final class Ecdh {

    private static final String CURVE = "secp256r1";

    private Ecdh() {
    }

    /** 生成一次性 P-256 密钥对 */
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec(CURVE));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("ECDH keypair generation failed", e);
        }
    }

    /** ECDH 共享密钥（32 字节） */
    public static byte[] sharedSecret(PrivateKey privateKey, PublicKey publicKey) {
        try {
            javax.crypto.KeyAgreement keyAgreement = javax.crypto.KeyAgreement.getInstance("ECDH");
            keyAgreement.init(privateKey);
            keyAgreement.doPhase(publicKey, true);
            return keyAgreement.generateSecret();
        } catch (Exception e) {
            throw new IllegalStateException("ECDH agreement failed", e);
        }
    }

    /** 公钥编码为 SPKI DER */
    public static byte[] encodePublicKey(PublicKey publicKey) {
        return publicKey.getEncoded();
    }

    /** 从 SPKI DER 解码公钥 */
    public static PublicKey decodePublicKey(byte[] spki) {
        try {
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid ECDH public key", e);
        }
    }
}
