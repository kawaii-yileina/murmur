package com.im.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.auth.crypto.EncryptedPayload;
import com.im.auth.crypto.EncryptedResponse;
import com.im.auth.crypto.EncryptedResult;
import com.im.auth.crypto.RestCryptoContext;
import com.im.auth.crypto.RestCryptoException;
import com.im.auth.crypto.RestCryptoInterceptor;
import com.im.auth.crypto.RestCryptoResponseAdvice;
import com.im.auth.crypto.RestCryptoService;
import com.im.common.model.Result;
import com.im.core.crypto.AesGcm;
import com.im.core.crypto.CryptoConstants;
import com.im.core.crypto.CryptoFormat;
import com.im.core.crypto.CryptoProperties;
import com.im.core.crypto.Ecdh;
import com.im.core.crypto.Hkdf;
import com.im.core.crypto.ServerKeyManager;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 响应加密：服务端派生 -> 客户端解密、上下文/缺头/篡改拒绝、多 key，以及 MockMvc 响应不含明文。
 */
class RestCryptoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PATH = "/api/messages/history";

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] unb64(String value) {
        return Base64.getDecoder().decode(value);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String ctx(String method, String path) {
        return CryptoFormat.hex(CryptoFormat.sha256((method + " " + path).getBytes(StandardCharsets.UTF_8)));
    }

    private static CryptoProperties enabledProps() {
        KeyPair pair = Ecdh.generateKeyPair();
        CryptoProperties props = new CryptoProperties();
        props.setEnabled(true);
        props.setKeyId("k1");
        props.setPrivateKey(b64(pair.getPrivate().getEncoded()));
        props.setPublicKey(b64(pair.getPublic().getEncoded()));
        return props;
    }

    private static RestCryptoService service(CryptoProperties props) {
        return new RestCryptoService(props, new ServerKeyManager(props), MAPPER);
    }

    private static MockHttpServletRequest request(KeyPair client, byte[] cNonce, String keyId, String ctxHex) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
        request.addHeader(RestCryptoService.HEADER_EPH, b64(client.getPublic().getEncoded()));
        request.addHeader(RestCryptoService.HEADER_NONCE, b64(cNonce));
        request.addHeader(RestCryptoService.HEADER_KEY_ID, keyId);
        request.addHeader(RestCryptoService.HEADER_CTX, ctxHex);
        return request;
    }

    private static byte[] decryptPayload(EncryptedPayload payload, KeyPair client, byte[] cNonce,
                                         String ctxHex, byte[] serverStaticSpki) {
        byte[] sNonce = unb64(payload.sNonce());
        byte[] serverPub = unb64(payload.pub());
        byte[] clientPub = client.getPublic().getEncoded();
        byte[] dhEs = Ecdh.sharedSecret(client.getPrivate(), Ecdh.decodePublicKey(serverStaticSpki));
        byte[] dhEe = Ecdh.sharedSecret(client.getPrivate(), Ecdh.decodePublicKey(serverPub));
        byte[] ikm = concat(dhEs, dhEe);
        byte[] transcript = CryptoFormat.restTranscript(
                CryptoConstants.VERSION, payload.keyId(), ctxHex, cNonce, sNonce, clientPub, serverPub);
        byte[] prk = Hkdf.extract(sNonce, ikm);
        byte[] key = CryptoFormat.deriveRestKey(prk, payload.keyId(), transcript);
        return AesGcm.decrypt(key, unb64(payload.iv()), transcript, unb64(payload.ct()));
    }

    @Test
    void restTranscriptGoldenVector() {
        // 锁定跨语言 transcript 字符串格式（客户端 restCrypto.ts 必须逐字节一致）
        byte[] transcript = CryptoFormat.restTranscript(1, "k1",
                "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
                new byte[]{1, 2, 3, 4}, new byte[]{5, 6, 7, 8},
                new byte[]{0x11, 0x22, 0x33}, new byte[]{0x44, 0x55, 0x66});
        assertThat(CryptoFormat.hex(transcript))
                .isEqualTo("504ef824546f6a74677b2e94f8b0e9fa826414280df2cec0a7be1a3e5aec19ed");
    }

    @Test
    void serverEncryptsAndClientDecrypts() throws Exception {
        CryptoProperties props = enabledProps();
        RestCryptoService restCrypto = service(props);
        KeyPair client = Ecdh.generateKeyPair();
        byte[] cNonce = new byte[4];
        new SecureRandom().nextBytes(cNonce);
        String ctxHex = ctx("GET", PATH);

        RestCryptoContext context = restCrypto.begin(request(client, cNonce, "k1", ctxHex));
        EncryptedPayload payload = restCrypto.encrypt(Map.of("content", "top-secret 消息"), context);

        byte[] plaintext = decryptPayload(payload, client, cNonce, ctxHex, unb64(props.getPublicKey()));
        JsonNode json = MAPPER.readTree(plaintext);
        assertThat(json.get("content").asText()).isEqualTo("top-secret 消息");
        assertThat(payload.keyId()).isEqualTo("k1");
        assertThat(payload.ct()).doesNotContain("top-secret");
    }

    @Test
    void missingHeaderIsRejected() {
        RestCryptoService restCrypto = service(enabledProps());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
        assertThatThrownBy(() -> restCrypto.begin(request))
                .isInstanceOf(RestCryptoException.class)
                .hasMessageContaining("X-Crypto-Eph");
    }

    @Test
    void wrongContextIsRejected() {
        RestCryptoService restCrypto = service(enabledProps());
        KeyPair client = Ecdh.generateKeyPair();
        byte[] cNonce = new byte[4];
        String wrongCtx = ctx("GET", "/api/messages/sync");
        assertThatThrownBy(() -> restCrypto.begin(request(client, cNonce, "k1", wrongCtx)))
                .isInstanceOf(RestCryptoException.class)
                .hasMessageContaining("不匹配");
    }

    @Test
    void unknownKeyIdIsRejected() {
        RestCryptoService restCrypto = service(enabledProps());
        KeyPair client = Ecdh.generateKeyPair();
        byte[] cNonce = new byte[4];
        assertThatThrownBy(() -> restCrypto.begin(request(client, cNonce, "k9", ctx("GET", PATH))))
                .isInstanceOf(RestCryptoException.class)
                .hasMessageContaining("未知");
    }

    @Test
    void rotatedKeyIsSupported() throws Exception {
        KeyPair k1 = Ecdh.generateKeyPair();
        KeyPair k2 = Ecdh.generateKeyPair();
        CryptoProperties props = new CryptoProperties();
        props.setEnabled(true);
        props.setKeyId("k1");
        props.setPrivateKey(b64(k1.getPrivate().getEncoded()));
        props.setPublicKey(b64(k1.getPublic().getEncoded()));
        CryptoProperties.KeyEntry entry = new CryptoProperties.KeyEntry();
        entry.setKeyId("k2");
        entry.setPrivateKey(b64(k2.getPrivate().getEncoded()));
        entry.setPublicKey(b64(k2.getPublic().getEncoded()));
        props.setKeys(List.of(entry));

        RestCryptoService restCrypto = service(props);
        KeyPair client = Ecdh.generateKeyPair();
        byte[] cNonce = new byte[4];
        String ctxHex = ctx("GET", PATH);
        EncryptedPayload payload = restCrypto.encrypt(Map.of("text", "k2-secret"),
                restCrypto.begin(request(client, cNonce, "k2", ctxHex)));

        assertThat(payload.keyId()).isEqualTo("k2");
        byte[] plaintext = decryptPayload(payload, client, cNonce, ctxHex, k2.getPublic().getEncoded());
        assertThat(MAPPER.readTree(plaintext).get("text").asText()).isEqualTo("k2-secret");
    }

    @Test
    void tamperedCiphertextFails() {
        CryptoProperties props = enabledProps();
        RestCryptoService restCrypto = service(props);
        KeyPair client = Ecdh.generateKeyPair();
        byte[] cNonce = new byte[4];
        String ctxHex = ctx("GET", PATH);
        EncryptedPayload payload = restCrypto.encrypt(Map.of("text", "secret"),
                restCrypto.begin(request(client, cNonce, "k1", ctxHex)));
        byte[] ct = unb64(payload.ct());
        ct[0] ^= 0x01;
        EncryptedPayload tampered = new EncryptedPayload(payload.v(), payload.keyId(), payload.sNonce(),
                payload.pub(), payload.iv(), b64(ct));
        assertThatThrownBy(() -> decryptPayload(tampered, client, cNonce, ctxHex,
                unb64(props.getPublicKey())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mockMvcResponseContainsCiphertextOnly() throws Exception {
        CryptoProperties props = enabledProps();
        RestCryptoService restCrypto = service(props);
        RestCryptoInterceptor interceptor = new RestCryptoInterceptor(restCrypto, MAPPER);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SecretController())
                .setControllerAdvice(new RestCryptoResponseAdvice(restCrypto))
                .addInterceptors(interceptor)
                .build();

        KeyPair client = Ecdh.generateKeyPair();
        byte[] cNonce = new byte[4];
        String ctxHex = ctx("GET", "/api/test/secret");
        String body = mockMvc.perform(get("/api/test/secret")
                        .header(RestCryptoService.HEADER_EPH, b64(client.getPublic().getEncoded()))
                        .header(RestCryptoService.HEADER_NONCE, b64(cNonce))
                        .header(RestCryptoService.HEADER_KEY_ID, "k1")
                        .header(RestCryptoService.HEADER_CTX, ctxHex))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enc").exists())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("top-secret"))))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        EncryptedResult result = MAPPER.readValue(body, EncryptedResult.class);
        byte[] plaintext = decryptPayload(result.enc(), client, cNonce, ctxHex,
                unb64(props.getPublicKey()));
        assertThat(MAPPER.readTree(plaintext).get("text").asText()).isEqualTo("top-secret");
    }

    @Test
    void mockMvcMissingHeadersReturns400() throws Exception {
        CryptoProperties props = enabledProps();
        RestCryptoService restCrypto = service(props);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SecretController())
                .setControllerAdvice(new RestCryptoResponseAdvice(restCrypto))
                .addInterceptors(new RestCryptoInterceptor(restCrypto, MAPPER))
                .build();

        mockMvc.perform(get("/api/test/secret"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void disabledCryptoKeepsPlaintextForCompatibility() throws Exception {
        CryptoProperties props = new CryptoProperties();
        RestCryptoService restCrypto = service(props);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SecretController())
                .setControllerAdvice(new RestCryptoResponseAdvice(restCrypto))
                .addInterceptors(new RestCryptoInterceptor(restCrypto, MAPPER))
                .build();

        mockMvc.perform(get("/api/test/secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.text").value("top-secret"))
                .andExpect(jsonPath("$.enc").doesNotExist());
    }

    @RestController
    static class SecretController {
        @EncryptedResponse
        @GetMapping("/api/test/secret")
        public Result<Map<String, String>> secret() {
            return Result.ok(Map.of("text", "top-secret"));
        }
    }
}
