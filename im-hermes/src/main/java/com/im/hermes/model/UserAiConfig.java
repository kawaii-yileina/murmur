package com.im.hermes.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_user_ai_config")
public class UserAiConfig {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String baseUrl;
    private String apiKey;
    private String model;
    private Integer maxTokens;
    private Double temperature;
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
