package com.im.core.chat.gateway;

import com.elaina.chat.bootstrap.defaults.NoopConnection;
import com.elaina.chat.core.dispatcher.Dispatcher;
import com.elaina.chat.core.gateway.MessageGateway;
import com.elaina.chat.core.handler.HandlerContext;
import com.elaina.chat.core.handler.HandlerContextFactory;
import com.elaina.chat.core.message.ChatMessage;
import com.elaina.chat.core.registry.UserConnectionRegistry;
import com.elaina.chat.core.router.SendResult;
import com.im.core.chat.router.RealtimeMessageRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * 集群感知消息网关：
 * - 需要拦截器/持久化的消息继续走框架 Dispatcher，由普通聊天链路和 Outbox 完成跨节点投递；
 * - 跳过拦截器的瞬时消息统一走 RealtimeMessageRouter，按 Redis 路由本节点直写、远端经 RocketMQ 投递。
 * 这样所有 messageGateway.sendToUser(..., false) 调用点都自动具备多实例能力。
 */
@Component
@RequiredArgsConstructor
public class ClusterMessageGateway implements MessageGateway {

    private static final String SYSTEM_FROM = "SYSTEM";

    private final Dispatcher dispatcher;
    private final UserConnectionRegistry registry;
    private final HandlerContextFactory handlerContextFactory;
    private final RealtimeMessageRouter realtimeRouter;

    @Override
    public SendResult sendToUser(String userId, ChatMessage message) {
        return sendToUser(userId, message, true);
    }

    @Override
    public SendResult sendToUser(String userId, ChatMessage message, boolean runInterceptors) {
        ChatMessage enriched = enrich(message, userId, false);
        if (!runInterceptors) {
            realtimeRouter.sendToUser(userId, enriched);
            return SendResult.of(List.of(userId), Collections.emptyList(), Collections.emptyList());
        }
        return dispatch(enriched, true);
    }

    @Override
    public SendResult sendToGroup(String groupId, ChatMessage message) {
        return sendToGroup(groupId, message, true);
    }

    @Override
    public SendResult sendToGroup(String groupId, ChatMessage message, boolean runInterceptors) {
        ChatMessage enriched = enrich(message, groupId, true);
        if (!runInterceptors) {
            realtimeRouter.sendToGroup(groupId, enriched);
            return SendResult.of(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }
        return dispatch(enriched, true);
    }

    @Override
    public SendResult sendToUsers(List<String> userIds, ChatMessage message) {
        return sendToUsers(userIds, message, true);
    }

    @Override
    public SendResult sendToUsers(List<String> userIds, ChatMessage message, boolean runInterceptors) {
        SendResult result = SendResult.empty();
        for (String userId : userIds) {
            result = merge(result, sendToUser(userId, message, runInterceptors));
        }
        return result;
    }

    private SendResult dispatch(ChatMessage message, boolean runInterceptors) {
        HandlerContext ctx = handlerContextFactory.create(NoopConnection.getInstance(), registry);
        if (runInterceptors) {
            return dispatcher.dispatch(message, ctx);
        }
        return dispatcher.dispatch(message, ctx, Collections.emptyList());
    }

    private ChatMessage enrich(ChatMessage message, String targetId, boolean group) {
        if (message.getFrom() == null || message.getFrom().isBlank()) {
            message = message.withFrom(SYSTEM_FROM);
        }
        return message.withTo(targetId).withGroup(group);
    }

    private SendResult merge(SendResult base, SendResult extra) {
        return SendResult.of(
                concat(base.getDelivered(), extra.getDelivered()),
                concat(base.getOffline(), extra.getOffline()),
                concat(base.getFailed(), extra.getFailed()));
    }

    private List<String> concat(List<String> base, List<String> extra) {
        return Stream.concat(base.stream(), extra.stream()).toList();
    }
}
