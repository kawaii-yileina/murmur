package com.im.core.group.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_group_member")
public class GroupMember {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long groupId;
    private Long userId;
    private Integer role;
    private LocalDateTime joinedAt;
}
