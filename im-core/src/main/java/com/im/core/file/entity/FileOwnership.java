package com.im.core.file.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话内附件的文件归属：图片/视频/语音/文件消息落库时登记，下载时据此做会话级鉴权。
 * 头像、表情等非附件文件不登记，按公开放行。
 */
@Data
@TableName("t_file_ownership")
public class FileOwnership {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 存储文件 key（如 2026/10/uuid.jpg），同一文件可对应多条归属（被多个会话引用） */
    private String fileKey;

    /** 上传者（消息发送者） */
    private Long ownerUserId;

    /** 0=私聊 1=群聊 */
    private Integer chatType;

    /** 私聊为对方 userId，群聊为 groupId */
    private Long targetId;

    private LocalDateTime createdAt;
}
