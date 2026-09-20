package com.im.hermes.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_ai_session_meta")
public class AiSessionMeta {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String sessionId;
    private String systemPrompt;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
