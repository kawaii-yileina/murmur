package com.im.core.emoji.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户收藏的表情包（存图片 URL）。
 */
@Data
@TableName("t_emoji_sticker")
public class EmojiSticker {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String url;
    private LocalDateTime createdAt;
}
