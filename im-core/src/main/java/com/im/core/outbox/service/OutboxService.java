package com.im.core.outbox.service;

import com.elaina.chat.core.message.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.common.constant.ChatConstants;
import com.im.common.utils.MentionsUtils;
import com.im.core.cluster.config.ClusterProperties;
import com.im.core.conversation.mapper.ConversationSeqMapper;
import com.im.core.conversation.service.ConversationService;
import com.im.core.message.entity.Message;
import com.im.core.message.service.MessageService;
import com.im.core.message.util.ConversationIds;
import com.im.core.outbox.dto.OutboxPayload;
import com.im.core.outbox.entity.Outbox;
import com.im.core.outbox.mapper.OutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Outbox 事务服务：把"权威 seq 分配 + 消息落库 + 会话更新 + Outbox 入队"收敛到同一个数据库事务。
 *
 * 事务边界：本类方法均为 @Transactional，由 {@code MessagePersistHook} / {@code OutboxDispatcher}
 * 等外部 Bean 调用（Spring 代理生效），调用方不再自行吞异常，保证失败整体回滚。
 *
 * 序号分配：t_conversation_seq 的 INSERT ... ON DUPLICATE KEY UPDATE 持行锁，
 * 使同一会话的并发事务串行化，seq 分配顺序 = 事务提交顺序，且随事务回滚。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final MessageService messageService;
    private final ConversationService conversationService;
    private final ConversationSeqMapper conversationSeqMapper;
    private final OutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final ClusterProperties clusterProperties;

    /**
     * 同一事务内：分配权威 seq、落库消息、更新会话预览/未读、写 Outbox。
     *
     * @param recipients  全部接收目标（含本地已投递者）
     * @param successList 本地已成功直投的接收目标
     * @return 本次分配的会话序号
     */
    @Transactional(rollbackFor = Exception.class)
    public long persistAndEnqueue(ChatMessage msg, List<String> recipients, List<String> successList) {
        int chatType = msg.isGroup() ? 1 : 0;
        String conversationId = ConversationIds.of(msg);

        // 1) 同事务内原子分配权威 seq（行锁串行化同会话并发；回滚一并回滚）
        conversationSeqMapper.bump(conversationId);
        Long seqValue = conversationSeqMapper.lastSeq(conversationId);
        long seq = seqValue != null ? seqValue : 1L;

        // 2) 落库消息（含 seq）
        Message persisted = messageService.persistMessage(msg, seq);

        // 3) 会话预览 / 未读数
        updateConversations(msg, recipients, chatType);

        // 4) Outbox 入队（仅跨节点需要；单机模式不写）
        if (clusterProperties.isEnabled()) {
            enqueueOutbox(msg, persisted, conversationId, chatType, seq, recipients, successList);
        }
        return seq;
    }

    private void updateConversations(ChatMessage msg, List<String> recipients, int chatType) {
        Long senderId = parseLong(msg.getFrom());
        Long receiverId = parseLong(msg.getTo());
        if (senderId == null || receiverId == null) {
            return;
        }
        String lastMsg = truncateMessage(msg);
        Long recipientTargetId = chatType == 1 ? receiverId : senderId;

        Set<String> mentionedUserIds = chatType == 1
                ? MentionsUtils.extractUserIds(getHeader(msg, ChatConstants.HEADER_MENTIONS))
                : Set.of();
        boolean mentionAll = mentionedUserIds.contains(ChatConstants.MENTION_ALL);

        for (String recipientId : recipients) {
            Long rid = parseLong(recipientId);
            if (rid != null) {
                boolean mentioned = mentionAll || mentionedUserIds.contains(recipientId);
                conversationService.updateConversation(rid, recipientTargetId, chatType, msg.getId(), lastMsg, mentioned);
            }
        }
        conversationService.updateConversation(senderId, receiverId, chatType, msg.getId(), lastMsg);
    }

    private void enqueueOutbox(ChatMessage msg, Message persisted, String conversationId, int chatType,
                               long seq, List<String> recipients, List<String> successList) {
        List<String> remoteTargets = new ArrayList<>();
        for (String recipientId : recipients) {
            if (recipientId != null && (successList == null || !successList.contains(recipientId))) {
                remoteTargets.add(recipientId);
            }
        }
        if (remoteTargets.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        Outbox outbox = new Outbox();
        outbox.setMsgId(persisted.getMsgId());
        outbox.setConversationId(conversationId);
        outbox.setSeq(seq);
        outbox.setChatType(chatType);
        outbox.setPayload(writePayload(new OutboxPayload(remoteTargets, msg)));
        outbox.setStatus(Outbox.STATUS_PENDING);
        outbox.setRetryCount(0);
        outbox.setNextRetryAt(now);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        outboxMapper.insert(outbox);
    }

    /**
     * 短事务领取一批到期任务：SELECT ... FOR UPDATE SKIP LOCKED 锁行 -> 置 PROCESSING + 租约 -> 提交。
     * 返回已领取的 Outbox 行，调用方在事务提交后（锁已释放）再做网络发送。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Outbox> claim(int batch, long leaseSeconds) {
        int window = Math.max(1, clusterProperties.getOutboxConversationWindow());
        List<Long> ids = outboxMapper.selectClaimableIds(batch, window, LocalDateTime.now());
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LocalDateTime leaseUntil = LocalDateTime.now().plusSeconds(leaseSeconds);
        outboxMapper.markProcessing(ids, leaseUntil);
        List<Outbox> claimed = new ArrayList<>(outboxMapper.selectBatchIds(ids));
        // selectBatchIds 不保证顺序；按 seq 升序处理以保证同会话批内有序
        claimed.sort(Comparator.comparingLong(Outbox::getSeq).thenComparingLong(Outbox::getId));
        return claimed;
    }

    @Transactional(rollbackFor = Exception.class)
    public void markDone(Long id) {
        outboxMapper.markDone(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markRetry(Long id, int retryCount, LocalDateTime nextRetryAt, String lastError) {
        outboxMapper.markRetry(id, retryCount, nextRetryAt, truncate(lastError, 500));
    }

    @Transactional(rollbackFor = Exception.class)
    public void markDead(Long id, int retryCount, String lastError) {
        outboxMapper.markDead(id, retryCount, truncate(lastError, 500));
    }

    // ---------- 死信处置 ----------

    /** 列出 DEAD 任务（供人工处置），limit 上限 200 */
    public List<Outbox> listDead(int limit) {
        return outboxMapper.selectDead(Math.max(1, Math.min(limit, 200)));
    }

    /** 死信重投：重置为 PENDING 重新入队；返回是否命中 DEAD 行 */
    @Transactional(rollbackFor = Exception.class)
    public boolean retryDead(Long id) {
        return outboxMapper.retryDead(id) > 0;
    }

    /** 死信丢弃：删除 DEAD 行；返回是否命中 */
    @Transactional(rollbackFor = Exception.class)
    public boolean discardDead(Long id) {
        return outboxMapper.deleteDead(id) > 0;
    }

    // ---------- 读取（用于监控）----------

    public long countPending() {
        return outboxMapper.countByStatus(Outbox.STATUS_PENDING);
    }

    public long countDead() {
        return outboxMapper.countByStatus(Outbox.STATUS_DEAD);
    }

    public long countDuePending() {
        return outboxMapper.countDuePending(LocalDateTime.now());
    }

    public LocalDateTime oldestPending() {
        return outboxMapper.oldestPending();
    }

    // ---------- helpers ----------

    private String writePayload(OutboxPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox payload for message " + payload.getMessage().getId(), e);
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

    private String getHeader(ChatMessage msg, String key) {
        if (msg.getHeaders() == null) return "";
        Object value = msg.getHeaders().get(key);
        return value != null ? String.valueOf(value) : "";
    }

    private double parseDuration(ChatMessage msg) {
        try {
            return Double.parseDouble(getHeader(msg, "duration"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String truncateMessage(ChatMessage msg) {
        String content = msg.getContent();
        String type = msg.getType();
        if (content == null) return "";
        return switch (type) {
            case "IMAGE" -> "[图片]";
            case "VIDEO" -> "[视频]";
            case "VOICE" -> "[语音] " + Math.round(parseDuration(msg)) + "\"";
            case "CONTACT_CARD" -> "[个人名片]";
            case "FILE" -> {
                String name = getHeader(msg, "originalName");
                String text = "[文件] " + name;
                yield text.length() > 50 ? text.substring(0, 50) + "..." : text;
            }
            default -> content.length() > 50 ? content.substring(0, 50) + "..." : content;
        };
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }
}
