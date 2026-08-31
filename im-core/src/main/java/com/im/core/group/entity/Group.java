package com.im.core.group.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_group")
public class Group {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    private String avatar;
    private Long ownerId;
    private LocalDateTime createdAt;
}
