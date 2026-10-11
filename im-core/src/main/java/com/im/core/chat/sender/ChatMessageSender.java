package com.im.core.chat.sender;

import com.elaina.chat.core.connection.Connection;
import com.elaina.chat.core.message.ChatMessage;
import com.elaina.chat.core.offline.OfflineHandler;
import com.elaina.chat.core.router.AbstractSender;
import com.elaina.chat.core.router.SendResult;
import com.im.core.chat.registry.MultiConnectionRegistry;
import com.im.core.cluster.ConnAttributes;
import com.im.core.cluster.MessageDedupService;
import com.im.core.cluster.config.ClusterProperties;
import com.im.core.cluster.route.RouteRegistry;
import com.im.core.message.service.OfflineMessageService;
import com.im.core.message.util.MessageHeaders;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 消息发送器（多端）。
 *
 * - 单机模式：向该用户全部本地设备直投；无任何在线设备则落离线队列（key 为用户级）。
 * - 集群模式：向该用户全部本地设备直投并写去重标记；远端/离线目标交由 Outbox 统一投递。
 *   仅当该用户"所有在线设备均已本地直投"时才将其排除出 Outbox；否则保留在 Outbox 中，
 *   由派发器投递到其他节点的设备（本地已投递设备靠去重标记跳过，避免重复）。
 *
 * 一致性限制（已知并记录）：本地直投发生在消息持久化之前（框架先 send 后 postSend），
 * 进程在落库前崩溃会导致该消息本地已投递但未落库，由客户端按 seq/历史补拉对齐。
 * 返回的 successList 语义 = "无需 Outbox 派发的接收方（本地已完整投递）"，用于避免冗余 Outbox 行。
 */
@Slf4j
@Component
public class ChatMessageSender extends AbstractSender {

    private final MultiConnectionRegistry multiRegistry;
    private final OfflineMessageService offlineMessageService;
    private final ClusterProperties clusterProperties;
    private final RouteRegistry routeRegistry;
    private final MessageDedupService dedupService;

    @Autowired
    public ChatMessageSender(MultiConnectionRegistry registry, OfflineHandler offlineHandler,
                             OfflineMessageService offlineMessageService,
                             ClusterProperties clusterProperties,
                             RouteRegistry routeRegistry,
                             MessageDedupService dedupService) {
        super(registry, offlineHandler);
        this.multiRegistry = registry;
        this.offlineMessageService = offlineMessageService;
        this.clusterProperties = clusterProperties;
        this.routeRegistry = routeRegistry;
        this.dedupService = dedupService;
    }

    @Override
    public SendResult send(ChatMessage message, List<String> recipients, Connection sender) {
        Set<String> fullyDelivered = new LinkedHashSet<>();
        Set<String> offline = new LinkedHashSet<>();
        List<String> failed = new ArrayList<>();

        if (recipients == null || recipients.isEmpty()) {
            return SendResult.of(new ArrayList<>(), new ArrayList<>(), failed);
        }

        boolean cluster = clusterProperties.isEnabled();
        String msgId = message.getId();
        // 剥离服务端保留 header（如转发带入的旧 seq），避免污染接收端排序
        ChatMessage outbound = MessageHeaders.stripReserved(message);

        for (String recipientId : recipients) {
            if (recipientId == null) {
                continue;
            }
            ArrayList<Connection> conns = new ArrayList<>(multiRegistry.getConnections(recipientId));
            if (!conns.isEmpty()) {
                Set<String> deliveredDevices = new HashSet<>();
                for (Connection conn : conns) {
                    conn.write(outbound);
                    String deviceId = ConnAttributes.deviceId(conn);
                    deliveredDevices.add(deviceId);
                    if (cluster) {
                        try {
                            dedupService.markProcessed(msgId, recipientId, deviceId);
                        } catch (Exception e) {
                            log.warn("Failed to mark dedup for user {} device {}: {}", recipientId, deviceId, e.getMessage());
                        }
                    }
                }
                if (!cluster) {
                    fullyDelivered.add(recipientId);
                } else {
                    // 仅当可证明"该用户所有在线设备均已本地投递"时才排除 Outbox 派发；
                    // 路由查询失败时 fail-open（不排除，保留在 Outbox，由派发器兜底，宁可多投不丢）
                    try {
                        Set<String> onlineDevices = routeRegistry.deviceIds(recipientId);
                        if (!onlineDevices.isEmpty() && deliveredDevices.containsAll(onlineDevices)) {
                            fullyDelivered.add(recipientId);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to resolve devices for user {}, keep in Outbox: {}", recipientId, e.getMessage());
                    }
                }
                continue;
            }

            if (cluster) {
                // 集群模式：远端/离线交由 Outbox 统一处理，这里不再落离线以免重复
                continue;
            }

            offline.add(recipientId);
            try {
                offlineMessageService.storeOfflineMessage(recipientId, outbound);
            } catch (Exception e) {
                // 单机模式：离线队列不可用不阻断发送，仅告警（重连后可由 seq 补拉兜底）
                log.error("Failed to store offline message for user {}: {}", recipientId, e.getMessage());
            }
            notifyOffline(outbound, recipientId, sender);
        }

        return SendResult.of(new ArrayList<>(fullyDelivered), new ArrayList<>(offline), failed);
    }
}
