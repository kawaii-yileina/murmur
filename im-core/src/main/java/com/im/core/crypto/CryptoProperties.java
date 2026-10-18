package com.im.core.crypto;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用层消息加密配置。见 docs/消息体加密设计.md。
 */
@Data
@Component
@ConfigurationProperties(prefix = "im.crypto")
public class CryptoProperties {

    /** 是否启用应用层消息加密（客户端就绪前默认关闭，避免既有客户端无法通信）；启用即对所有连接强制加密 */
    private boolean enabled = false;

    /** 服务端静态密钥标识（限定字符集 [a-zA-Z0-9-]）；多 key 时为首选 keyId */
    private String keyId = "k1";

    /** 服务端静态私钥（PKCS8 DER，Base64）；为空则启动时生成临时密钥并告警（仅供开发） */
    @ToString.Exclude
    private String privateKey;

    /** 服务端静态公钥（SPKI DER，Base64）；用于导出给客户端内置 pin（可选） */
    private String publicKey;

    /**
     * 轮换期附加密钥集合：与上面的单 key 字段并存，按客户端 HELLO 的 keyId 选择。
     * 私钥为空的条目会被忽略，便于分批给多个节点下发同一份配置。
     */
    private List<KeyEntry> keys = new ArrayList<>();

    @Data
    public static class KeyEntry {
        private String keyId;
        @ToString.Exclude
        private String privateKey;
        private String publicKey;
    }
}
