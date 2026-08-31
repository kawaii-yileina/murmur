package com.im.core.friend.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_friend_request")
public class FriendRequest {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long fromId;
    private Long toId;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
