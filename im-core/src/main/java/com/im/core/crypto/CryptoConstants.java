package com.im.core.crypto;

/**
 * 应用层消息加密协议常量。见 docs/消息体加密设计.md。
 */
public final class CryptoConstants {

    private CryptoConstants() {
    }

    /** 协议版本（客户端只发单一版本，不做回退协商） */
    public static final int VERSION = 1;

    /** 握手帧类型（明文，仅含公钥与随机数） */
    public static final String FRAME_HELLO = "CRYPTO_HELLO";
    public static final String FRAME_HELLO_ACK = "CRYPTO_HELLO_ACK";
    public static final String FRAME_CONFIRM = "CRYPTO_CONFIRM";

    /** HKDF info 前缀 */
    public static final String INFO_PREFIX = "im-msg-v1";
    /** REST 响应加密 HKDF/transcript 前缀 */
    public static final String INFO_PREFIX_REST = "im-rest-v1";
    /** 方向标签 */
    public static final String DIR_C2S = "c2s";
    public static final String DIR_S2C = "s2c";

    /** nonce 前缀长度（字节） */
    public static final int NONCE_PREFIX_LEN = 4;
    /** GCM nonce 长度（字节） */
    public static final int NONCE_LEN = 12;
    /** 密钥长度（字节） */
    public static final int KEY_LEN = 32;
    /** 会话密钥发送计数器硬上限（NIST SP 800-38D 安全界预留边际） */
    public static final long MAX_COUNTER = 1L << 31;

    /** CRYPTO_CONFIRM 占用的保留序号；业务帧 seq 从 1 开始 */
    public static final long CONFIRM_SEQ = 0L;

    /** keyId 允许的字符集（避免 HKDF info 标签歧义） */
    public static final String KEY_ID_PATTERN = "[a-zA-Z0-9-]{1,32}";

    /** 加密标记 header：置 1 表示消息体已加密（供接收端区分明文/密文） */
    public static final String HEADER_ENCRYPTED = "enc";
    /** 密文信封头：协议版本 */
    public static final String HEADER_CRYPTO_V = "cv";
    /** 密文信封头：keyId */
    public static final String HEADER_CRYPTO_KEY_ID = "ckid";
    /** 密文信封头：nonce/iv（Base64） */
    public static final String HEADER_CRYPTO_IV = "civ";
    /** 密文信封头：连接内方向序号（与 nonce 计数器一致） */
    public static final String HEADER_CRYPTO_SEQ = "cseq";

    /** 握手前出向缓冲上限（条）：超限丢弃，依赖 /sync 补拉 */
    public static final int PRE_HANDSHAKE_BUFFER_LIMIT = 64;
}
