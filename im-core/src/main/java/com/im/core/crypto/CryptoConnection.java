package com.im.core.crypto;

import java.security.PrivateKey;

/**
 * 单个连接的加密状态：握手状态机 + 会话密钥 + nonce 前缀 + 收发计数器。
 * 由 pipeline 加解密 handler 在连接维度持有（channel attribute），单 EventLoop 串行访问，无需加锁。
 */
public class CryptoConnection {

    public enum State {
        /** 已通过 JWT 认证，等待应用层握手 */
        AUTHED,
        /** 握手中（已发 HELLO_ACK，等待对端 CONFIRM） */
        HANDSHAKING,
        /** 握手完成，可收发加密业务帧 */
        ESTABLISHED,
        /** 关闭中 */
        CLOSING
    }

    private State state = State.AUTHED;
    private String keyId;

    private byte[] c2sKey;
    private byte[] s2cKey;
    private byte[] cNonce;
    private byte[] sNonce;
    private byte[] transcript;

    /** 服务端临时私钥：握手期使用，进入 ESTABLISHED 后清除以保护前向保密 */
    private PrivateKey serverEphemeral;

    /** 服务端出向（s2c）下一个序号；0 保留给 CONFIRM，业务从 1 开始 */
    private long sendSeq = CryptoConstants.CONFIRM_SEQ;
    /** 客户端入向（c2s）下一个期望序号 */
    private long recvSeq = CryptoConstants.CONFIRM_SEQ;

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public String getKeyId() {
        return keyId;
    }

    public byte[] getC2sKey() {
        return c2sKey;
    }

    public byte[] getS2cKey() {
        return s2cKey;
    }

    public byte[] getCNonce() {
        return cNonce;
    }

    public byte[] getSNonce() {
        return sNonce;
    }

    public byte[] getTranscript() {
        return transcript;
    }

    public PrivateKey getServerEphemeral() {
        return serverEphemeral;
    }

    /** 握手结果写入：进入 HANDSHAKING，保存会话密钥与 nonce 前缀 */
    public void applyHandshake(String keyId, byte[] c2sKey, byte[] s2cKey, byte[] cNonce, byte[] sNonce,
                               byte[] transcript, PrivateKey serverEphemeral) {
        this.keyId = keyId;
        this.c2sKey = c2sKey;
        this.s2cKey = s2cKey;
        this.cNonce = cNonce;
        this.sNonce = sNonce;
        this.transcript = transcript;
        this.serverEphemeral = serverEphemeral;
        this.sendSeq = CryptoConstants.CONFIRM_SEQ;
        this.recvSeq = CryptoConstants.CONFIRM_SEQ;
        this.state = State.HANDSHAKING;
    }

    /** 密钥确认通过：进入 ESTABLISHED，清除临时私钥 */
    public void markEstablished() {
        this.serverEphemeral = null;
        this.sendSeq = CryptoConstants.CONFIRM_SEQ + 1;
        this.recvSeq = CryptoConstants.CONFIRM_SEQ + 1;
        this.state = State.ESTABLISHED;
    }

    /** 出向当前序号（用于 AAD 与 nonce，未自增） */
    public long peekSendSeq() {
        return sendSeq;
    }

    /** 出向自增；越界抛异常（需重连换密钥） */
    public void incSendSeq() {
        if (++sendSeq >= CryptoConstants.MAX_COUNTER) {
            throw new IllegalStateException("send counter exhausted, reconnect required");
        }
    }

    /** 入向当前期望序号 */
    public long peekRecvSeq() {
        return recvSeq;
    }

    /** 入向自增；越界抛异常 */
    public void incRecvSeq() {
        if (++recvSeq >= CryptoConstants.MAX_COUNTER) {
            throw new IllegalStateException("recv counter exhausted, reconnect required");
        }
    }

    /** 强制清理所有密钥材料 */
    public void clear() {
        c2sKey = null;
        s2cKey = null;
        cNonce = null;
        sNonce = null;
        transcript = null;
        serverEphemeral = null;
        state = State.CLOSING;
    }
}
