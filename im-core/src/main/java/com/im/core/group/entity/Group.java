package com.im.core.group.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.im.core.group.dto.GroupMemberAvatar;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("t_group")
public class Group {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    private String avatar;
    private Long ownerId;
    private LocalDateTime createdAt;

    /** 前 9 个成员头像，仅用于群头像拼接，不落库 */
    @TableField(exist = false)
    private List<GroupMemberAvatar> memberAvatars;
}
