package com.im.auth.crypto;

/**
 * im-rest-v1 响应信封：客户端用请求期临时私钥 + 内置服务端静态公钥解密 ct。
 */
public record EncryptedPayload(
        int v,
        String keyId,
        String sNonce,
        String pub,
        String iv,
        String ct) {
}
