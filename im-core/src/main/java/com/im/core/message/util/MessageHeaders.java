package com.im.core.message.util;

import com.elaina.chat.core.message.ChatMessage;
import com.im.common.constant.ChatConstants;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 下行消息 header 清洗：剥离仅服务端内部使用的保留键，避免客户端伪造或转发的旧值污染下行语义。
 *
 * 典型场景：转发会把被转发消息的 headers 原样带入新消息（含旧的 seq），若直接下发，接收端会据过期 seq 排序错位。
 * 因此投递前统一剥离以下保留键，由服务端在持久化投递路径重新附带权威 seq：
 * - seq（会话权威序号，仅服务端分配）
 * - ack / serverMsgId / delivered（ACK 回执专用，不应出现在聊天消息中）
 *
 * 注：ChatMessage 不可变（withHeaders 返回副本），无保留键时原样返回，避免不必要的复制。
 */
public final class MessageHeaders {

    private static final Set<String> RESERVED = Set.of(
            ChatConstants.HEADER_SEQ,
            ChatConstants.HEADER_ACK,
            ChatConstants.HEADER_SERVER_MSG_ID,
            ChatConstants.HEADER_DELIVERED);

    private MessageHeaders() {
    }

    /** 返回剥离保留 header 的消息副本；无需剥离时原样返回 */
    public static ChatMessage stripReserved(ChatMessage message) {
        if (message == null) {
            return null;
        }
        Map<String, Object> headers = message.getHeaders();
        if (headers == null || headers.isEmpty()) {
            return message;
        }
        boolean reserved = false;
        for (String key : RESERVED) {
            if (headers.containsKey(key)) {
                reserved = true;
                break;
            }
        }
        if (!reserved) {
            return message;
        }
        Map<String, Object> clean = new HashMap<>(headers);
        RESERVED.forEach(clean::remove);
        return message.withHeaders(clean);
    }
}
