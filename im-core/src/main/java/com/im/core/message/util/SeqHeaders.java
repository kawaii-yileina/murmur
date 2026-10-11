package com.im.core.message.util;

import com.elaina.chat.core.message.ChatMessage;
import com.im.common.constant.ChatConstants;

import java.util.HashMap;
import java.util.Map;

/**
 * 持久化投递路径在消息 headers 附带会话权威 seq，供客户端按 seq 排序。
 *
 * ChatMessage 不可变（withHeaders 返回副本），故返回带 seq 的新副本，不修改入参。
 * 用于 Outbox 本地投递、跨节点消费回投与离线补投；本节点直投发生在分配 seq 之前，不带 seq（由 /sync 回填）。
 */
public final class SeqHeaders {

    private SeqHeaders() {
    }

    /** 返回附带 headers.seq 的消息副本；message 为空或 seq<=0 时原样返回 */
    public static ChatMessage withSeq(ChatMessage message, long seq) {
        if (message == null || seq <= 0) {
            return message;
        }
        Map<String, Object> headers = new HashMap<>(message.getHeaders());
        headers.put(ChatConstants.HEADER_SEQ, String.valueOf(seq));
        return message.withHeaders(headers);
    }
}
