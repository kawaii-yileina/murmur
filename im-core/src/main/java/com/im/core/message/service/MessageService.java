package com.im.core.message.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.elaina.chat.core.message.ChatMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.common.constant.ChatConstants;
import com.im.common.enums.ChatType;
import com.im.common.enums.MessageType;
import com.im.common.exception.BizException;
import com.im.core.conversation.service.ConversationService;
import com.im.core.file.service.FileOwnershipService;
import com.im.core.group.entity.GroupMember;
import com.im.core.group.mapper.GroupMemberMapper;
import com.im.core.message.dto.MessageSyncResult;
import com.im.core.message.entity.Message;
import com.im.core.message.event.MessageRecalledEvent;
import com.im.core.message.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageMapper messageMapper;
    private final ConversationService conversationService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final FileOwnershipService fileOwnershipService;

    /** 撤回时限（分钟） */
    private static final int RECALL_TIMEOUT_MINUTES = 2;

    /** 增量同步单次返回上限 */
    private static final int MAX_SYNC_LIMIT = 200;

    /**
     * 不落库的 header 键：内部 ACK 回执字段，以及已有独立列的 replyTo / mentions。
     * 注意：clientMsgId 需保留落库——客户端断线丢 ACK 后，凭同步/历史返回的 clientMsgId
     * 把本地乐观消息与服务端副本对齐，避免重复气泡。
     */
    private static final Set<String> NON_PERSISTED_HEADER_KEYS = Set.of(
            ChatConstants.HEADER_ACK,
            ChatConstants.HEADER_SERVER_MSG_ID,
            ChatConstants.HEADER_DELIVERED,
            ChatConstants.HEADER_REPLY_TO,
            ChatConstants.HEADER_MENTIONS,
            ChatConstants.HEADER_SEQ);

    /**
     * 落库消息，并写入本次由 t_conversation_seq 同事务分配的会话序号 seq。
     * 调用方（OutboxService）在其事务内调用，本方法不单独开启事务。
     *
     * @return 落库后的消息实体（含服务端 msgId 与 id）
     */
    public Message persistMessage(ChatMessage chatMessage, long seq) {
        Message msg = new Message();
        String msgId = chatMessage.getId();
        msg.setMsgId(msgId != null && !msgId.isBlank() ? msgId : UUID.randomUUID().toString());
        msg.setSenderId(Long.parseLong(chatMessage.getFrom()));
        msg.setReceiverId(Long.parseLong(chatMessage.getTo()));
        msg.setChatType(chatMessage.isGroup() ? 1 : 0);
        msg.setMsgType(chatMessage.getType());
        msg.setContent(chatMessage.getContent());
        msg.setReplyTo(resolveReplyTo(chatMessage));
        msg.setMentions(resolveMentions(chatMessage));
        msg.setHeaders(resolveHeaders(chatMessage));
        msg.setSeq(seq);
        msg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(msg);
        // 附件类消息同事务登记文件归属（图片/视频/语音/文件），供下载时做会话级鉴权
        fileOwnershipService.bindIfAttachment(
                msg.getSenderId(), msg.getChatType(), msg.getReceiverId(),
                msg.getMsgType(), msg.getContent());
        return msg;
    }

    /**
     * 展示型 headers 透传落库（剔除内部字段与已有独立列的键）。
     * 历史消息不带 headers 会导致语音时长、文件名等丢失（引用/转发/会话预览显示为 0 或空）。
     */
    private String resolveHeaders(ChatMessage chatMessage) {
        Map<String, Object> headers = chatMessage.getHeaders();
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        Map<String, Object> display = new LinkedHashMap<>();
        headers.forEach((key, value) -> {
            if (value != null && !NON_PERSISTED_HEADER_KEYS.contains(key)) {
                display.put(key, value);
            }
        });
        if (display.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(display);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize headers for message {}", chatMessage.getId(), e);
            return null;
        }
    }

    /** 引用回复信息由发送端放在 headers.replyTo（JSON 字符串），透传落库 */
    private String resolveReplyTo(ChatMessage chatMessage) {
        if (chatMessage.getHeaders() == null) return null;
        Object replyTo = chatMessage.getHeaders().get(ChatConstants.HEADER_REPLY_TO);
        return replyTo != null ? String.valueOf(replyTo) : null;
    }

    /** @提及信息由发送端放在 headers.mentions（JSON 数组字符串），透传落库 */
    private String resolveMentions(ChatMessage chatMessage) {
        if (chatMessage.getHeaders() == null) return null;
        Object mentions = chatMessage.getHeaders().get(ChatConstants.HEADER_MENTIONS);
        return mentions != null ? String.valueOf(mentions) : null;
    }

    /** 按 msgId 查询单条消息（无则返回 null） */
    public Message getByMsgId(String msgId) {
        if (msgId == null || msgId.isBlank()) {
            return null;
        }
        return messageMapper.selectOne(new LambdaQueryWrapper<Message>().eq(Message::getMsgId, msgId));
    }

    /**
     * 撤回消息：仅本人、发送后 2 分钟内、未撤回过的消息可撤回。
     * 撤回后 msg_type 置为 RECALL 并清空内容，同时向会话内其他成员推送
     * SYSTEM 事件（message_recall:msgId），前端据此替换气泡文案。
     */
    public Message recallMessage(Long userId, String msgId) {
        Message msg = messageMapper.selectOne(
            new LambdaQueryWrapper<Message>().eq(Message::getMsgId, msgId)
        );
        if (msg == null) {
            throw new BizException("消息不存在");
        }
        if (!msg.getSenderId().equals(userId)) {
            throw new BizException("只能撤回自己发送的消息");
        }
        if (ChatConstants.MSG_TYPE_CALL.equals(msg.getMsgType())) {
            throw new BizException("通话记录不支持撤回");
        }
        if (MessageType.RECALL.getType().equals(msg.getMsgType())) {
            return msg;
        }
        if (msg.getCreatedAt() == null
                || msg.getCreatedAt().isBefore(LocalDateTime.now().minusMinutes(RECALL_TIMEOUT_MINUTES))) {
            throw new BizException("超过 " + RECALL_TIMEOUT_MINUTES + " 分钟的消息不能撤回");
        }

        msg.setMsgType(MessageType.RECALL.getType());
        msg.setContent("");
        messageMapper.updateById(msg);

        conversationService.markRecalled(msg.getMsgId(), msg.getSenderId());
        eventPublisher.publishEvent(new MessageRecalledEvent(msg, userId));
        return msg;
    }

    public List<Message> getHistory(Long userId, Long targetId, int chatType, int page, int size) {
        boolean group = chatType == ChatType.GROUP.getCode();
        if (group && !isGroupMember(targetId, userId)) {
            throw new BizException(403, "无权访问该群会话");
        }

        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<Message>()
                .eq(Message::getChatType, chatType);

        if (group) {
            wrapper.eq(Message::getReceiverId, targetId);
        } else {
            wrapper.and(w -> w
                .and(inner -> inner.eq(Message::getSenderId, userId).eq(Message::getReceiverId, targetId))
                .or(inner -> inner.eq(Message::getSenderId, targetId).eq(Message::getReceiverId, userId))
            );
        }

        wrapper.orderByDesc(Message::getCreatedAt)
                .last("LIMIT " + size + " OFFSET " + (page - 1) * size);
        return messageMapper.selectList(wrapper);
    }

    /**
     * 会话增量同步：按权威 seq 升序返回 seq &gt; sinceSeq 的持久化消息，供客户端断线重连补拉。
     * 客户端以返回的 lastSeq 作为本地游标，配合 msgId 去重，不把序号空号当作丢消息。
     * 说明：仅返回已分配 seq 的消息；迁移前的历史消息 seq 为 NULL，请走 /history 分页。
     *
     * @param userId    请求方（私聊按会话双方、群聊校验成员，防止越权读取）
     * @param targetId  会话对端（私聊为对方 userId，群聊为 groupId）
     * @param chatType  0 私聊 / 1 群聊
     * @param sinceSeq  客户端已持有的最大 seq（首次可传 0）
     * @param limit     单次上限（1~200）
     */
    public MessageSyncResult sync(Long userId, Long targetId, int chatType, long sinceSeq, int limit) {
        int size = Math.min(Math.max(limit, 1), MAX_SYNC_LIMIT);
        boolean group = chatType == ChatType.GROUP.getCode();
        if (group && !isGroupMember(targetId, userId)) {
            throw new BizException(403, "无权访问该群会话");
        }

        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<Message>()
                .eq(Message::getChatType, chatType);
        if (group) {
            wrapper.eq(Message::getReceiverId, targetId);
        } else {
            wrapper.and(w -> w
                .and(inner -> inner.eq(Message::getSenderId, userId).eq(Message::getReceiverId, targetId))
                .or(inner -> inner.eq(Message::getSenderId, targetId).eq(Message::getReceiverId, userId))
            );
        }
        // 多取一条判断 hasMore，避免额外 count 查询
        wrapper.gt(Message::getSeq, sinceSeq)
                .orderByAsc(Message::getSeq)
                .last("LIMIT " + (size + 1));

        List<Message> rows = messageMapper.selectList(wrapper);
        boolean hasMore = rows.size() > size;
        List<Message> messages = hasMore ? new ArrayList<>(rows.subList(0, size)) : rows;
        long lastSeq = messages.isEmpty() ? sinceSeq : messages.get(messages.size() - 1).getSeq();
        return new MessageSyncResult(messages, lastSeq, hasMore);
    }

    private boolean isGroupMember(Long groupId, Long userId) {
        return groupMemberMapper.selectCount(
            new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId)
        ) > 0;
    }
}
