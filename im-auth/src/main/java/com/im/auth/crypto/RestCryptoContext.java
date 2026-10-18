package com.im.auth.crypto;

import org.jetbrains.annotations.NotNull;

/**
 * 单次 REST 响应的加密上下文：服务端临时密钥已生成，密钥/iv/transcript 已派生。
 */
public record RestCryptoContext(
        String keyId,
        byte[] sNonce,
        byte[] serverPub,
        byte[] transcript,
        byte[] key,
        byte[] iv) {

    @NotNull
    @Override
    public String toString() {
        return "RestCryptoContext{keyId='" + keyId + "', keyBytes=" + key.length + "}";
    }
}
