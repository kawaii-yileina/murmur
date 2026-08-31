package com.im.core.message.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.elaina.chat.core.message.ChatMessage;
import com.im.core.message.entity.Message;
import com.im.core.message.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageMapper messageMapper;

    public void persistMessage(ChatMessage chatMessage, List<String> recipients) {
        Message msg = new Message();
        msg.setMsgId(UUID.randomUUID().toString());
        msg.setSenderId(Long.parseLong(chatMessage.getFrom()));
        msg.setReceiverId(Long.parseLong(chatMessage.getTo()));
        msg.setChatType(chatMessage.isGroup() ? 1 : 0);
        msg.setMsgType(chatMessage.getType());
        msg.setContent(chatMessage.getContent());
        msg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(msg);
    }

    public List<Message> getHistory(Long userId, Long targetId, int chatType, int page, int size) {
        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<Message>()
                .eq(Message::getChatType, chatType);

        if (chatType == 1) {
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
}
