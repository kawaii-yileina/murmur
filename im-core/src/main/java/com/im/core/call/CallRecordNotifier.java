package com.im.core.call;

import com.elaina.chat.core.gateway.MessageGateway;
import com.elaina.chat.core.message.ChatMessage;
import com.im.common.constant.ChatConstants;
import com.im.core.call.entity.CallSession;
import com.im.core.call.mapper.CallSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 通话结束处理（G2）：落 {@code t_call_session} 并在双方会话下发一条 {@code CALL} 类型消息。
 *
 * 位于框架链路之外（不被框架组件依赖），可安全注入 {@link MessageGateway} 走普通持久化投递链路：
 * - 记录：按 call_id 幂等写入（INSERT IGNORE）。
 * - 聊天条目：以 callId 作为消息 id（天然幂等）；向被叫持久化投递（触发持久化/会话预览/离线），
 *   另向主叫下发一条不持久化的实时副本，使主叫客户端也即时看到通话记录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallRecordNotifier {

    private final CallSessionMapper callSessionMapper;
    private final MessageGateway messageGateway;

    @Async
    @EventListener(CallEndedEvent.class)
    public void onCallEnded(CallEndedEvent e) {
        try {
            persistSession(e);
        } catch (Exception ex) {
            log.warn("Persist call session failed: callId={}: {}", e.callId(), ex.getMessage());
        }
        try {
            sendCallMessage(e);
        } catch (Exception ex) {
            log.warn("Send call message failed: callId={}: {}", e.callId(), ex.getMessage());
        }
    }

    private void persistSession(CallEndedEvent e) {
        Long caller = parseLong(e.callerId());
        Long callee = parseLong(e.calleeId());
        if (caller == null || callee == null) {
            return;
        }
        CallSession s = new CallSession();
        s.setCallId(e.callId());
        s.setCallerId(caller);
        s.setCalleeId(callee);
        s.setMediaType("video".equals(e.media()) ? 1 : 0);
        s.setStatus(e.status());
        s.setStartedAt(toLdt(e.startedAtMillis()));
        s.setAnsweredAt(e.answeredAtMillis() > 0 ? toLdt(e.answeredAtMillis()) : null);
        s.setEndedAt(toLdt(e.endedAtMillis()));
        s.setDurationSeconds((int) Math.max(0L, e.durationSeconds()));
        s.setCreatedAt(LocalDateTime.now());
        callSessionMapper.insertIgnore(s);
    }

    private void sendCallMessage(CallEndedEvent e) {
        if (e.callerId() == null || e.calleeId() == null) {
            return;
        }
        String media = e.media() == null ? "audio" : e.media();
        // 被叫：走普通持久化投递（落 t_message、更新会话、离线兜底）
        messageGateway.sendToUser(e.calleeId(), callMessage(e.callId(), e.callerId(), e.callerId(), e.calleeId(), media, e.status(), e.durationSeconds()));
        // 主叫：实时副本（不持久化）。注意框架会强制 to=接收者，故 from 取对端，避免主叫端出现"与自己"的会话
        messageGateway.sendToUser(e.callerId(), callMessage(e.callId(), e.calleeId(), e.callerId(), e.callerId(), media, e.status(), e.durationSeconds()), false);
    }

    private ChatMessage callMessage(String callId, String from, String callerId, String to, String media, String status, long duration) {
        ChatMessage msg = ChatMessage.builder()
                .id(callId)
                .type(ChatConstants.MSG_TYPE_CALL)
                .from(from)
                .to(to)
                .group(false)
                .content("")
                .build();
        msg.addHeader(ChatConstants.HEADER_CALL_ID, callId);
        msg.addHeader(ChatConstants.HEADER_CALL_CALLER, callerId);
        msg.addHeader(ChatConstants.HEADER_CALL_MEDIA, media);
        msg.addHeader(ChatConstants.HEADER_CALL_STATUS, status);
        msg.addHeader(ChatConstants.HEADER_DURATION, String.valueOf(duration));
        return msg;
    }

    private Long parseLong(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDateTime toLdt(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }
}
