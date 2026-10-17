package com.im.core.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 密文载荷编解码：把消息的 content 与 headers 作为一个整体序列化为明文字节（AEAD 的明文），
 * 或从解密后的字节还原。序列化只在各自一端构造/解析，不需要两端的字节规范一致。
 */
@Component
@RequiredArgsConstructor
public class CryptoPayloadCodec {

    private final ObjectMapper objectMapper;

    /** 构造明文：{content, headers} */
    public byte[] encode(String content, Map<String, Object> headers) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        payload.put("headers", headers == null ? Collections.emptyMap() : headers);
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new IllegalStateException("crypto payload encode failed", e);
        }
    }

    /** 解析明文：返回 [content, headers]（headers 为可变 Map，供后续 handler 追加） */
    @SuppressWarnings("unchecked")
    public Decoded decode(byte[] plaintext) {
        try {
            Map<String, Object> payload = objectMapper.readValue(plaintext, Map.class);
            String content = (String) payload.get("content");
            Object rawHeaders = payload.get("headers");
            Map<String, Object> headers = rawHeaders instanceof Map
                    ? new LinkedHashMap<>((Map<String, Object>) rawHeaders)
                    : new LinkedHashMap<>();
            return new Decoded(content, headers);
        } catch (Exception e) {
            throw new IllegalStateException("crypto payload decode failed", e);
        }
    }

    public record Decoded(String content, Map<String, Object> headers) {
    }
}
