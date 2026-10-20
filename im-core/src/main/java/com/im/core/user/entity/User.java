package com.im.core.user.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_user")
public class User {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String username;
    private String email;
    private Integer emailVerified;
    private String password;
    private String nickname;
    private String avatar;
    private String signature;
    private Integer status;
    private Integer banned;
    private String banReason;
    private LocalDateTime bannedAt;
    private Long bannedBy;
    /** 封禁到期时间；null 表示永久封禁 */
    private LocalDateTime banExpireAt;
    /** 是否已注销（软删除）0否1是 */
    private Integer deleted;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
