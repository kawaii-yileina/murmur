package com.im.core.chat.sender;

import com.elaina.chat.core.connection.Connection;
import com.elaina.chat.core.message.ChatMessage;
import com.elaina.chat.core.offline.OfflineHandler;
import com.elaina.chat.core.registry.UserConnectionRegistry;
import com.elaina.chat.core.router.AbstractSender;
import com.elaina.chat.core.router.SendResult;
import com.im.core.cluster.config.ClusterProperties;
import com.im.core.message.service.OfflineMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 消息发送器。
 *
 * - 单机模式（cluster 关闭）：本地连接直投；无连接则落离线队列（保持原行为）。
 * - 集群模式（cluster 开启）：只做本地连接直投；远端/离线目标不在本方法处理，
 *   由持久化侧写入 Outbox 后交由 OutboxDispatcher 按当前路由统一投递。
 *
 * 一致性限制（已知并记录）：本地直投发生在消息持久化之前（框架先 send 后 postSend），
 * 因此本地在线目标可能先于落库收到消息；进程在落库前崩溃会导致该消息本地已投递但未落库，
 * 由客户端按 seq/历史补拉对齐。本阶段不保证"本地直投与落库强一致"。
 */
@Slf4j
@Component
public class ChatMessageSender extends AbstractSender {

    private final OfflineMessageService offlineMessageService;
    private final ClusterProperties clusterProperties;

    @Autowired
    public ChatMessageSender(UserConnectionRegistry registry, OfflineHandler offlineHandler,
                             OfflineMessageService offlineMessageService,
                             ClusterProperties clusterProperties) {
        super(registry, offlineHandler);
        this.offlineMessageService = offlineMessageService;
        this.clusterProperties = clusterProperties;
    }

    @Override
    public SendResult send(ChatMessage message, List<String> recipients, Connection sender) {
        Set<String> delivered = new LinkedHashSet<>();
        Set<String> offline = new LinkedHashSet<>();
        List<String> failed = new ArrayList<>();

        if (recipients == null || recipients.isEmpty()) {
            return SendResult.of(new ArrayList<>(), new ArrayList<>(), failed);
        }

        boolean cluster = clusterProperties.isEnabled();
        for (String recipientId : recipients) {
            if (recipientId == null) {
                continue;
            }
            Connection conn = registry.getConnection(recipientId);
            if (conn != null && conn.isActive()) {
                conn.write(message);
                delivered.add(recipientId);
                continue;
            }
            if (cluster) {
                // 集群模式：远端/离线交由 Outbox 统一处理，这里不再落离线，避免重复
                continue;
            }
            offline.add(recipientId);
            offlineMessageService.storeOfflineMessage(recipientId, message);
            notifyOffline(message, recipientId, sender);
        }

        return SendResult.of(new ArrayList<>(delivered), new ArrayList<>(offline), failed);
    }
}
