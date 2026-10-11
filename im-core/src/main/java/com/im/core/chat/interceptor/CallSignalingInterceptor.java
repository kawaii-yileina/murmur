package com.im.core.chat.interceptor;

import com.elaina.chat.core.handler.HandlerContext;
import com.elaina.chat.core.handler.MessageInterceptor;
import com.elaina.chat.core.message.ChatMessage;
import com.im.common.constant.ChatConstants;
import com.im.core.call.CallService;
import com.im.core.call.CallSignalRouter;
import com.im.core.call.entity.CallSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 通话信令拦截器：服务端状态维护（忙线/好友/归属校验）+ 经 {@link CallSignalRouter} 投递。
 *
 * 位于框架拦截器链路（经 ChatConfiguration 手动构造注册），仅依赖 CallService 与 CallSignalRouter，
 * 不依赖 MessageGateway，避免成环。CALL_* 一律在此接管（投递后 return false，不进入普通消息链路，
 * 从而不落库、不进 Outbox/离线、不计 ACK）。
 */
@Slf4j
@RequiredArgsConstructor
public class CallSignalingInterceptor implements MessageInterceptor {

    private final CallService callService;
    private final CallSignalRouter callSignalRouter;

    @Override
    public boolean preSend(ChatMessage message, HandlerContext ctx, List<String> targets) {
        String type = message.getType();
        if (!ChatConstants.isCallSignal(type)) {
            return true;
        }
        String callId = header(message, ChatConstants.HEADER_CALL_ID);
        String from = message.getFrom();
        try {
            switch (type) {
                case ChatConstants.CALL_INVITE: {
                    CallService.Result result = callService.onInvite(message);
                    if (result == CallService.Result.BUSY) {
                        ctx.send(reply(ChatConstants.CALL_BUSY, message, "busy"));
                        return false;
                    }
                    if (result == CallService.Result.NOT_FRIEND) {
                        ctx.send(reply(ChatConstants.CALL_REJECT, message, "forbidden"));
                        return false;
                    }
                    if (result == CallService.Result.UNKNOWN) {
                        return false;
                    }
                    callSignalRouter.deliver(message);
                    return false;
                }
                case ChatConstants.CALL_RINGING:
                    if (!callService.isCallee(callId, from)) {
                        return false;
                    }
                    callService.onRinging(callId);
                    callSignalRouter.deliver(message);
                    return false;
                case ChatConstants.CALL_ACCEPT:
                    if (!callService.isCallee(callId, from)) {
                        return false;
                    }
                    callService.onAccept(callId);
                    // 通知主叫接听
                    callSignalRouter.deliver(message);
                    // 通知被叫其余设备停止振铃（接听设备自身忽略该结束信号）
                    callSignalRouter.deliver(acceptedElsewhere(message, from));
                    return false;
                case ChatConstants.CALL_REJECT:
                    if (!callService.isParticipant(callId, from)) {
                        return false;
                    }
                    callService.terminate(callId, CallSession.STATUS_REJECTED);
                    callSignalRouter.deliver(message);
                    return false;
                case ChatConstants.CALL_CANCEL:
                    if (!callService.isParticipant(callId, from)) {
                        return false;
                    }
                    callService.terminate(callId, CallSession.STATUS_CANCELED);
                    callSignalRouter.deliver(message);
                    return false;
                case ChatConstants.CALL_HANGUP:
                    if (!callService.isParticipant(callId, from)) {
                        return false;
                    }
                    callService.terminate(callId,
                            CallService.STATUS_ACTIVE.equals(callService.statusOf(callId))
                                    ? CallSession.STATUS_COMPLETED : CallSession.STATUS_CANCELED);
                    callSignalRouter.deliver(message);
                    return false;
                case ChatConstants.CALL_BUSY:
                    if (!callService.isParticipant(callId, from)) {
                        return false;
                    }
                    callService.terminate(callId, null);
                    callSignalRouter.deliver(message);
                    return false;
                case ChatConstants.CALL_OFFER:
                case ChatConstants.CALL_ANSWER:
                case ChatConstants.CALL_ICE:
                    if (!callService.isParticipant(callId, from)) {
                        return false;
                    }
                    callService.touch(callId);
                    callSignalRouter.deliver(message);
                    return false;
                default:
                    // 白名单内的未知/客户端不应上行的信令（如 CALL_TIMEOUT）：一律丢弃
                    return false;
            }
        } catch (Exception e) {
            // 信令异常不进入普通消息链路（放行会产生落库/双重投递），直接丢弃并由超时兜底
            log.warn("Call signaling handling failed: type={}, callId={}: {}", type, callId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean postSend(ChatMessage message, HandlerContext ctx, List<String> targets, List<String> successList) {
        return true;
    }

    /** 通知被叫其余设备"已被其他端接听"（结束振铃）；接听设备自身据 reason 忽略 */
    private ChatMessage acceptedElsewhere(ChatMessage accept, String calleeId) {
        ChatMessage m = ChatMessage.builder()
                .type(ChatConstants.CALL_HANGUP)
                .from(ChatConstants.SYSTEM_USER)
                .to(calleeId)
                .group(false)
                .content("")
                .build();
        String callId = header(accept, ChatConstants.HEADER_CALL_ID);
        if (callId != null) {
            m.addHeader(ChatConstants.HEADER_CALL_ID, callId);
        }
        m.addHeader(ChatConstants.HEADER_CALL_REASON, "accepted_elsewhere");
        return m;
    }

    /** 构造回给主叫的 SYSTEM 信令（如忙线/拒绝） */
    private ChatMessage reply(String replyType, ChatMessage origin, String reason) {
        ChatMessage reply = ChatMessage.builder()
                .type(replyType)
                .from(ChatConstants.SYSTEM_USER)
                .to(origin.getFrom())
                .group(false)
                .content("")
                .build();
        String callId = header(origin, ChatConstants.HEADER_CALL_ID);
        if (callId != null) {
            reply.addHeader(ChatConstants.HEADER_CALL_ID, callId);
        }
        reply.addHeader(ChatConstants.HEADER_CALL_REASON, reason);
        return reply;
    }

    private String header(ChatMessage msg, String key) {
        Map<String, Object> headers = msg.getHeaders();
        if (headers == null) {
            return null;
        }
        Object value = headers.get(key);
        return value != null ? String.valueOf(value) : null;
    }
}
