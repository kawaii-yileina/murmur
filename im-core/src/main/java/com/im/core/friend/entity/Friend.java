package com.im.core.friend.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_friend")
public class Friend {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long friendId;
    private String remark;
    private LocalDateTime createdAt;
}
