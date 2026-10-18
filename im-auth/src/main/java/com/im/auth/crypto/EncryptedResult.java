package com.im.auth.crypto;

/**
 * 加密响应外层：保留 code/message，data 替换为 enc 信封。
 */
public record EncryptedResult(int code, String message, EncryptedPayload enc) {
}
