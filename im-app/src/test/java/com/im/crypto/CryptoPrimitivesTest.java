package com.im.crypto;

import com.im.core.crypto.AesGcm;
import com.im.core.crypto.Ecdh;
import com.im.core.crypto.Hkdf;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 密码学原语单测：HKDF 用 RFC 5869 官方向量，AES-GCM/ECDH 做正确性与篡改校验。
 */
class CryptoPrimitivesTest {

    private static byte[] hex(String s) {
        int len = s.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(s.substring(i, i + 2), 16);
        }
        return out;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** RFC 5869 Appendix A.1 Test Case 1 */
    @Test
    void hkdfSha256Rfc5869Case1() {
        byte[] ikm = new byte[22];
        Arrays.fill(ikm, (byte) 0x0b);
        byte[] salt = hex("000102030405060708090a0b0c");
        byte[] info = hex("f0f1f2f3f4f5f6f7f8f9");

        byte[] prk = Hkdf.extract(salt, ikm);
        assertThat(toHex(prk))
                .isEqualTo("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5");

        byte[] okm = Hkdf.expand(prk, info, 42);
        assertThat(toHex(okm)).isEqualTo(
                "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
    }

    /** RFC 5869 Appendix A.3 Test Case 3（salt/info 为空） */
    @Test
    void hkdfSha256Rfc5869Case3() {
        byte[] ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] prk = Hkdf.extract(new byte[0], ikm);
        assertThat(toHex(prk))
                .isEqualTo("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04");
        byte[] okm = Hkdf.expand(prk, new byte[0], 42);
        assertThat(toHex(okm)).isEqualTo(
                "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8");
    }

    @Test
    void aesGcmRoundTripAndTamperDetection() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x2a);
        byte[] nonce = hex("0102030405060708090a0b0c");
        byte[] aad = "aad-context".getBytes();
        byte[] plaintext = "hello 消息体 🔒".getBytes();

        byte[] ciphertext = AesGcm.encrypt(key, nonce, aad, plaintext);
        assertThat(ciphertext).isNotEqualTo(plaintext);

        byte[] decrypted = AesGcm.decrypt(key, nonce, aad, ciphertext);
        assertThat(decrypted).isEqualTo(plaintext);

        // 篡改密文 → 校验失败
        byte[] tampered = ciphertext.clone();
        tampered[0] ^= 0x01;
        assertThatThrownBy(() -> AesGcm.decrypt(key, nonce, aad, tampered)).isInstanceOf(IllegalStateException.class);

        // AAD 不符 → 校验失败
        assertThatThrownBy(() -> AesGcm.decrypt(key, nonce, "other".getBytes(), ciphertext))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ecdhSharedSecretMatches() {
        KeyPair a = Ecdh.generateKeyPair();
        KeyPair b = Ecdh.generateKeyPair();

        byte[] abSpki = Ecdh.encodePublicKey(a.getPublic());
        byte[] baSpki = Ecdh.encodePublicKey(b.getPublic());

        byte[] secretA = Ecdh.sharedSecret(a.getPrivate(), Ecdh.decodePublicKey(baSpki));
        byte[] secretB = Ecdh.sharedSecret(b.getPrivate(), Ecdh.decodePublicKey(abSpki));

        assertThat(secretA).isEqualTo(secretB);
        assertThat(secretA).hasSize(32);
    }
}
