package com.im.core.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 协议格式工具：AAD、transcript、密钥派生标签、nonce 构造。
 * 所有格式用固定顺序的字符串/定长字节拼接，保证两端字节一致。
 */
public final class CryptoFormat {

    private CryptoFormat() {
    }

    /** AAD：绑定全部路由字段（含 id/时间戳），防在途篡改（改 to/group/type/from/id 即校验失败） */
    public static byte[] aad(int version, String keyId, String dir, long seq,
                             String type, String id, String from, String to, boolean group, long timestamp) {
        String s = CryptoConstants.INFO_PREFIX
                + "|v=" + version
                + "|keyId=" + keyId
                + "|dir=" + dir
                + "|seq=" + seq
                + "|type=" + nz(type)
                + "|id=" + nz(id)
                + "|from=" + nz(from)
                + "|to=" + nz(to)
                + "|group=" + group
                + "|ts=" + timestamp;
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** 握手 transcript：绑定版本/keyId/双方 nonce/双方临时公钥 */
    public static byte[] transcript(int version, String keyId, byte[] cNonce, byte[] sNonce,
                                    byte[] clientPub, byte[] serverPub) {
        String s = CryptoConstants.INFO_PREFIX
                + "|v=" + version
                + "|keyId=" + keyId
                + "|cnonce=" + b64(cNonce)
                + "|snonce=" + b64(sNonce)
                + "|cpub=" + b64(clientPub)
                + "|spub=" + b64(serverPub);
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    /** 按方向派生会话密钥：HKDF-Expand(PRK, INFO_PREFIX|keyId|label|hex(transcript), 32) */
    public static byte[] deriveKey(byte[] prk, String keyId, String label, byte[] transcript) {
        String info = CryptoConstants.INFO_PREFIX + "|" + keyId + "|" + label + "|" + hex(transcript);
        return Hkdf.expand(prk, info.getBytes(StandardCharsets.UTF_8), CryptoConstants.KEY_LEN);
    }

    /** nonce = 4 字节方向前缀 || 8 字节大端计数器 */
    public static byte[] nonce(byte[] prefix, long counter) {
        byte[] nonce = new byte[CryptoConstants.NONCE_LEN];
        System.arraycopy(prefix, 0, nonce, 0, CryptoConstants.NONCE_PREFIX_LEN);
        for (int i = 0; i < 8; i++) {
            nonce[CryptoConstants.NONCE_PREFIX_LEN + i] = (byte) (counter >>> (8 * (7 - i)));
        }
        return nonce;
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    public static byte[] unb64(String data) {
        return Base64.getDecoder().decode(data);
    }

    public static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
