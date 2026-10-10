package com.im.core.outbox.dto;

import com.elaina.chat.core.message.ChatMessage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Outbox 载荷：只保存逻辑接收目标（userId 列表）与原始消息；
 * 实际发送时由 OutboxDispatcher 按当前路由解析目标节点。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OutboxPayload {

    private List<String> recipients;

    private ChatMessage message;
}
