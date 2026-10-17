package com.im.core.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HKDF-SHA256（RFC 5869）：从高熵共享密钥派生会话密钥。
 * 仅用于高熵输入（ECDH 输出），不用于口令。
 */
public final class Hkdf {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final int HASH_LEN = 32;

    private Hkdf() {
    }

    /** HKDF-Extract：PRK = HMAC(salt, IKM) */
    public static byte[] extract(byte[] salt, byte[] ikm) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            byte[] effectiveSalt = (salt == null || salt.length == 0) ? new byte[HASH_LEN] : salt;
            mac.init(new SecretKeySpec(effectiveSalt, HMAC_SHA256));
            return mac.doFinal(ikm);
        } catch (Exception e) {
            throw new IllegalStateException("HKDF-Extract failed", e);
        }
    }

    /** HKDF-Expand：OKM = HKDF-Expand(PRK, info, length) */
    public static byte[] expand(byte[] prk, byte[] info, int length) {
        if (length <= 0 || length > 255 * HASH_LEN) {
            throw new IllegalArgumentException("invalid HKDF output length: " + length);
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(prk, HMAC_SHA256));
            byte[] okm = new byte[length];
            byte[] block = new byte[0];
            int pos = 0;
            int counter = 1;
            while (pos < length) {
                mac.update(block);
                if (info != null && info.length > 0) {
                    mac.update(info);
                }
                mac.update((byte) counter);
                block = mac.doFinal();
                int copy = Math.min(block.length, length - pos);
                System.arraycopy(block, 0, okm, pos, copy);
                pos += copy;
                counter++;
            }
            return okm;
        } catch (Exception e) {
            throw new IllegalStateException("HKDF-Expand failed", e);
        }
    }
}
