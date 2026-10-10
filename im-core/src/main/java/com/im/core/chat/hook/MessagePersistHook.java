package com.im.core.chat.hook;

import com.elaina.chat.core.connection.Connection;
import com.elaina.chat.core.message.ChatMessage;
import com.elaina.chat.core.persistence.MessagePersistentHook;
import com.im.core.chat.registry.MultiConnectionRegistry;
import com.im.common.constant.ChatConstants;
import com.im.core.outbox.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 持久化钩子：把"分配 seq + 消息落库 + 会话更新 + Outbox 入队"委托给 {@link OutboxService}
 * 在单个数据库事务内完成；事务提交后再回 ACK。
 *
 * 说明：
 * - 本方法由框架在异步线程调用；不再叠加 @Async（避免双重异步）。
 * - 事务边界在 OutboxService，异常在 OutboxService 内向上抛并回滚；本方法仅兜底记日志，
 *   避免异常逃逸到框架线程池。落库失败时不回 ACK，由发送端 ACK 超时兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessagePersistHook implements MessagePersistentHook {

    private static final Set<String> SKIP_TYPES = Set.of("SYSTEM", "HEARTBEAT");

    private final OutboxService outboxService;
    private final MultiConnectionRegistry connectionRegistry;

    @Override
    public void afterSend(ChatMessage msg, List<String> recipients, List<String> successList) {
        if (SKIP_TYPES.contains(msg.getType())) {
            return;
        }
        Long senderId = parseLong(msg.getFrom());
        Long receiverId = parseLong(msg.getTo());
        if (senderId == null || receiverId == null) {
            log.warn("Skipping persist for message with non-numeric ids: from={}, to={}", msg.getFrom(), msg.getTo());
            return;
        }

        try {
            outboxService.persistAndEnqueue(msg, recipients, successList);
        } catch (Exception e) {
            log.error("Failed to persist message {}, skip ACK: {}", msg.getId(), e.getMessage(), e);
            return;
        }
        sendAckToSender(msg, successList);
    }

    /**
     * 持久化成功后向发送者发送 ACK：SYSTEM 类型消息，headers 携带 ack=true、clientMsgId、
     * serverMsgId、delivered。前端据此把本地消息置为 sent 并替换 msgId。
     */
    private void sendAckToSender(ChatMessage msg, List<String> successList) {
        try {
            String clientMsgId = msg.getHeaders() != null
                    ? String.valueOf(msg.getHeaders().getOrDefault("clientMsgId", ""))
                    : "";
            if (clientMsgId.isBlank()) {
                return;
            }

            ChatMessage ack = ChatMessage.builder()
                    .type(ChatConstants.SYSTEM_USER)
                    .from(ChatConstants.SYSTEM_USER)
                    .to(msg.getFrom())
                    .group(false)
                    .content("")
                    .build();
            ack.addHeader("ack", "true");
            ack.addHeader("clientMsgId", clientMsgId);
            ack.addHeader("serverMsgId", msg.getId() != null ? msg.getId() : "");
            ack.addHeader("delivered", String.join(",", successList));

            Collection<Connection> conns = connectionRegistry.getConnections(msg.getFrom());
            for (Connection c : conns) {
                c.write(ack);
            }
            if (!conns.isEmpty()) {
                log.debug("Sent ACK for clientMsgId={}, serverMsgId={} to {} device(s)",
                        clientMsgId, msg.getId(), conns.size());
            } else {
                log.debug("Skip ACK, sender offline: user={}", msg.getFrom());
            }
        } catch (Exception e) {
            log.error("Failed to send ACK for message from {}", msg.getFrom(), e);
        }
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
