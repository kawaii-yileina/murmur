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
    private LocalDateTime createdAt;
}
