package com.im.hermes.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_ai_conversation")
public class AiConversation {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String sessionId;
    private String role;
    private String content;
    private LocalDateTime createdAt;
}
