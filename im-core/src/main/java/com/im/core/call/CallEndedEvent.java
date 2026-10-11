package com.im.core.call;

/**
 * 通话结束事件（G2）：由 {@link CallService} 在通话进入终态时发布，
 * 由链路外的监听器（{@link CallRecordNotifier}）落库并下发聊天内 CALL 条目（避免在拦截器链路注入 MessageGateway）。
 */
public record CallEndedEvent(
        String callId,
        String callerId,
        String calleeId,
        String media,
        String status,
        long durationSeconds,
        long startedAtMillis,
        long answeredAtMillis,
        long endedAtMillis) {
}
