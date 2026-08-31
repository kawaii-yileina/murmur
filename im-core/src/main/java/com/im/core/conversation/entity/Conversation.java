package com.im.core.conversation.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_conversation")
public class Conversation {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long targetId;
    private Integer chatType;
    private String lastMsgContent;
    private LocalDateTime lastMsgTime;
    private Integer unreadCount;
    private LocalDateTime updatedAt;
}
