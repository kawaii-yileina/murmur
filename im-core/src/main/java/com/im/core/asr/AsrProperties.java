package com.im.core.asr;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 本地 ASR（语音转文字）配置。应用通过 HTTP 调用本地 ASR 服务（不依赖外部 API），
 * 模型由 ASR 服务侧运行期挂载。默认关闭。
 */
@Data
@Component
@ConfigurationProperties(prefix = "im.asr")
public class AsrProperties {

    /** 是否启用语音转文字 */
    private boolean enabled = false;

    /** ASR 服务基址，如 http://localhost:8080 */
    private String url;

    /** 转写接口路径（whisper.cpp server 默认为 /inference） */
    private String path = "/inference";

    /** 可选：模型标识（作为表单字段 model 传递；whisper.cpp 的 /inference 会忽略此字段） */
    private String model;

    /** 可选：语言提示（如 zh） */
    private String language;

    /** 请求超时（秒） */
    private int timeoutSeconds = 60;

    /** 单条语音最大字节数（超过直接拒绝，0 表示不限制） */
    private long maxAudioBytes = 15L * 1024 * 1024;
}
