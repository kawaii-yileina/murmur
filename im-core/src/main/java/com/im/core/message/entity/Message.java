package com.im.core.message.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_message")
public class Message {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String msgId;
    private Long senderId;
    private Long receiverId;
    private Integer chatType;
    private String msgType;
    private String content;
    /** 引用回复信息（JSON：{msgId, senderId, msgType, summary}），无引用为 null */
    private String replyTo;
    /** 被 @ 成员信息（JSON 数组：[{userId,nickname}]），无提及为 null */
    private String mentions;
    /** 展示型头信息（JSON：duration/format/originalName/size/textContent 等），历史消息据此还原语音时长、文件名等 */
    private String headers;
    private LocalDateTime createdAt;
}
