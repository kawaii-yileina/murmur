package com.im.core.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * 本地 ASR 客户端：以 multipart/form-data 将音频 POST 到本地 ASR HTTP 服务，解析返回文本。
 * 使用 JDK17 内置 HttpClient（不新增依赖）。契约对齐 whisper.cpp `--server` 的 /inference：
 * 表单字段 file（音频），可选 model/language，响应 JSON {"text":"..."}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsrClient {

    private final AsrProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 转写音频，返回识别文本（可能为空字符串）。 */
    public String transcribe(byte[] audio, String filename) {
        if (!properties.isEnabled() || isBlank(properties.getUrl())) {
            throw new BizException("语音转文字未启用");
        }
        String endpoint = properties.getUrl().replaceAll("/+$", "") + properties.getPath();
        String boundary = "----imAsr" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = buildMultipart(boundary, audio, filename);

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("ASR service returned status {}: {}", response.statusCode(), preview(response.body()));
                throw new BizException("ASR 服务异常(" + response.statusCode() + ")");
            }
            String text = parseText(response.body());
            return text == null ? "" : text.trim();
        } catch (IOException e) {
            log.warn("Call ASR service failed: {}", e.getMessage());
            throw new BizException("调用 ASR 服务失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("语音转写被中断");
        }
    }

    private byte[] buildMultipart(String boundary, byte[] audio, String filename) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(audio.length + 512);
        writeText(out, "--" + boundary + "\r\n");
        writeText(out, "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n");
        writeText(out, "Content-Type: application/octet-stream\r\n\r\n");
        out.write(audio, 0, audio.length);
        writeText(out, "\r\n");
        if (!isBlank(properties.getModel())) {
            writeFormField(out, boundary, "model", properties.getModel());
        }
        if (!isBlank(properties.getLanguage())) {
            writeFormField(out, boundary, "language", properties.getLanguage());
        }
        writeText(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private void writeFormField(ByteArrayOutputStream out, String boundary, String name, String value) {
        writeText(out, "--" + boundary + "\r\n");
        writeText(out, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        writeText(out, value);
        writeText(out, "\r\n");
    }

    private void writeText(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
    }

    /** 解析 {"text": "..."}；非 JSON 时按纯文本处理。 */
    private String parseText(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            if (node.hasNonNull("text")) {
                return node.get("text").asText();
            }
            return "";
        } catch (Exception e) {
            return responseBody;
        }
    }

    private String preview(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
