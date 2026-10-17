package com.im.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.core.crypto.AesGcm;
import com.im.core.crypto.CryptoConnection;
import com.im.core.crypto.CryptoConstants;
import com.im.core.crypto.CryptoFormat;
import com.im.core.crypto.CryptoProperties;
import com.im.core.crypto.Ecdh;
import com.im.core.crypto.HandshakeService;
import com.im.core.crypto.Hkdf;
import com.im.core.crypto.ServerKeyManager;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 握手一致性单测：模拟客户端与服务器各自派生会话密钥，交叉验证 CONFIRM 与业务帧加解密。
 */
class CryptoHandshakeTest {

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    void aadGoldenFormat() {
        // 锁定 AAD 线上格式（客户端需逐字节复刻，防字段顺序/命名被无意改动）
        byte[] aad = CryptoFormat.aad(1, "k1", "s2c", 1, "TEXT", "m1", "u1", "u2", false, 1700000000000L);
        assertThat(new String(aad, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(
                "im-msg-v1|v=1|keyId=k1|dir=s2c|seq=1|type=TEXT|id=m1|from=u1|to=u2|group=false|ts=1700000000000");
    }

    @Test
    void handshakeAndBusinessFrameInterop() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CryptoProperties props = new CryptoProperties();
        props.setKeyId("k1");
        ServerKeyManager keyManager = new ServerKeyManager(props);
        HandshakeService service = new HandshakeService(keyManager, mapper);

        // ---- 客户端发起 HELLO ----
        KeyPair clientEphemeral = Ecdh.generateKeyPair();
        byte[] cNonce = new byte[CryptoConstants.NONCE_PREFIX_LEN];
        new SecureRandom().nextBytes(cNonce);
        byte[] clientPubSpki = clientEphemeral.getPublic().getEncoded();
        String helloJson = mapper.writeValueAsString(
                new HandshakeService.Hello(CryptoConstants.VERSION, "k1",
                        CryptoFormat.b64(cNonce), CryptoFormat.b64(clientPubSpki)));

        HandshakeService.Outcome outcome = service.start(helloJson);
        CryptoConnection conn = outcome.connection();
        assertThat(conn.getState()).isEqualTo(CryptoConnection.State.HANDSHAKING);

        HandshakeService.Ack ack = mapper.readValue(outcome.ackJson(), HandshakeService.Ack.class);
        assertThat(ack.v()).isEqualTo(CryptoConstants.VERSION);
        assertThat(ack.keyId()).isEqualTo("k1");

        // ---- 客户端各自派生密钥 ----
        byte[] sNonce = CryptoFormat.unb64(ack.sNonce());
        byte[] serverPubSpki = CryptoFormat.unb64(ack.pub());
        byte[] serverStaticSpki = keyManager.publicKeySpki("k1");

        byte[] dhEs = Ecdh.sharedSecret(clientEphemeral.getPrivate(), Ecdh.decodePublicKey(serverStaticSpki));
        byte[] dhEe = Ecdh.sharedSecret(clientEphemeral.getPrivate(), Ecdh.decodePublicKey(serverPubSpki));
        byte[] ikm = concat(dhEs, dhEe);
        byte[] transcript = CryptoFormat.transcript(CryptoConstants.VERSION, "k1", cNonce, sNonce,
                clientPubSpki, serverPubSpki);
        byte[] prk = Hkdf.extract(sNonce, ikm);
        byte[] clientC2s = CryptoFormat.deriveKey(prk, "k1", CryptoConstants.DIR_C2S, transcript);
        byte[] clientS2c = CryptoFormat.deriveKey(prk, "k1", CryptoConstants.DIR_S2C, transcript);

        // ---- 服务端 CONFIRM（seq=0）→ 客户端校验 ----
        byte[] serverConfirm = service.buildConfirm(conn);
        byte[] decryptedServerConfirm = AesGcm.decrypt(clientS2c,
                CryptoFormat.nonce(sNonce, CryptoConstants.CONFIRM_SEQ), transcript, serverConfirm);
        assertThat(decryptedServerConfirm).isEmpty();

        // ---- 客户端 CONFIRM（seq=0）→ 服务端校验并进入 ESTABLISHED ----
        byte[] clientConfirm = AesGcm.encrypt(clientC2s,
                CryptoFormat.nonce(cNonce, CryptoConstants.CONFIRM_SEQ), transcript, new byte[0]);
        assertThat(service.verifyConfirm(conn, clientConfirm)).isTrue();
        assertThat(conn.getState()).isEqualTo(CryptoConnection.State.ESTABLISHED);

        // ---- 业务帧（seq=1）：服务端加密 → 客户端解密 ----
        byte[] aad = CryptoFormat.aad(CryptoConstants.VERSION, "k1", CryptoConstants.DIR_S2C,
                conn.peekSendSeq(), "TEXT", "m1", "u1", "u2", false, 1700000000000L);
        byte[] plaintext = "hello 业务正文".getBytes();
        long seq = conn.peekSendSeq();
        byte[] ct = AesGcm.encrypt(conn.getS2cKey(), CryptoFormat.nonce(sNonce, seq), aad, plaintext);
        conn.incSendSeq();

        byte[] decrypted = AesGcm.decrypt(clientS2c, CryptoFormat.nonce(sNonce, seq), aad, ct);
        assertThat(decrypted).isEqualTo(plaintext);

        // ---- 篡改 AAD（改 to）→ 客户端校验失败 ----
        byte[] forgedAad = CryptoFormat.aad(CryptoConstants.VERSION, "k1", CryptoConstants.DIR_S2C,
                seq, "TEXT", "m1", "u1", "evil", false, 1700000000000L);
        boolean tampered;
        try {
            AesGcm.decrypt(clientS2c, CryptoFormat.nonce(sNonce, seq), forgedAad, ct);
            tampered = false;
        } catch (Exception e) {
            tampered = true;
        }
        assertThat(tampered).isTrue();
    }
}
