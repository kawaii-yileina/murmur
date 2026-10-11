package com.im.core.call;

import com.elaina.chat.core.message.ChatMessage;
import com.im.common.constant.ChatConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 振铃超时处理：扫描 Redis 超时队列，对仍未接听的呼叫通知双方并结束。
 *
 * - 单点领取：以 ZSet 原子移除（claimTimeout）保证多节点下只有一个节点处理。
 * - 通知：主叫收 CALL_TIMEOUT（无人接听），被叫收 CALL_CANCEL（停止振铃）。
 * - 投递经 CallSignalRouter（本地/跨节点）；本组件不在框架链路内，无成环风险。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "im.call.enabled", havingValue = "true", matchIfMissing = true)
public class CallTimeoutScheduler {

    private final CallService callService;
    private final CallSignalRouter callSignalRouter;

    @Scheduled(fixedDelay = 5000)
    public void scanTimeouts() {
        long now = System.currentTimeMillis();
        List<String> due;
        try {
            due = callService.dueCallIds(now);
        } catch (Exception e) {
            log.warn("Call timeout scan failed: {}", e.getMessage());
            return;
        }
        for (String callId : due) {
            try {
                if (!callService.claimTimeout(callId)) {
                    continue; // 已被其他节点/动作处理
                }
                Map<Object, Object> state = callService.state(callId);
                if (state == null || state.isEmpty()) {
                    continue;
                }
                if (CallService.STATUS_ACTIVE.equals(state.get("status"))) {
                    continue; // 已接听（正常已移出队列）：不清理活跃通话
                }
                Object callerId = state.get("callerId");
                Object calleeId = state.get("calleeId");
                // 原子终结（非 ACTIVE 才清理）；成功后发布 MISSED 记录
                if (!callService.finishTimeout(callId)) {
                    continue;
                }
                if (callerId != null) {
                    callSignalRouter.deliver(signal(ChatConstants.CALL_TIMEOUT, str(callerId), callId));
                }
                if (calleeId != null) {
                    callSignalRouter.deliver(signal(ChatConstants.CALL_CANCEL, str(calleeId), callId));
                }
                log.info("Call timeout -> MISSED: callId={}", callId);
            } catch (Exception e) {
                log.warn("Call timeout handling failed: callId={}: {}", callId, e.getMessage());
            }
        }
    }

    /** 长通话保活：定期续期 ACTIVE 通话状态与忙线，避免状态过期导致 HANGUP/ICE 被丢弃 */
    @Scheduled(fixedDelay = 300_000)
    public void renewActive() {
        try {
            callService.renewActiveCalls();
        } catch (Exception e) {
            log.warn("Renew active calls failed: {}", e.getMessage());
        }
    }

    private ChatMessage signal(String type, String to, String callId) {
        ChatMessage msg = ChatMessage.builder()
                .type(type)
                .from(ChatConstants.SYSTEM_USER)
                .to(to)
                .group(false)
                .content("")
                .build();
        msg.addHeader(ChatConstants.HEADER_CALL_ID, callId);
        return msg;
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
