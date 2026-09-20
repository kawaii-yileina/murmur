package com.im.core.emoji.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.common.utils.ThrowUtils;
import com.im.core.emoji.entity.EmojiSticker;
import com.im.core.emoji.mapper.EmojiStickerMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 表情包收藏：用户维度的图片 URL 收藏。
 */
@Service
@RequiredArgsConstructor
public class EmojiStickerService {

    private final EmojiStickerMapper emojiStickerMapper;

    public List<EmojiSticker> list(Long userId) {
        return emojiStickerMapper.selectList(
            new LambdaQueryWrapper<EmojiSticker>()
                .eq(EmojiSticker::getUserId, userId)
                .orderByDesc(EmojiSticker::getCreatedAt)
        );
    }

    /**
     * 添加收藏；同一用户重复收藏同一 URL 时直接返回已存在记录（幂等）。
     */
    public EmojiSticker add(Long userId, String url) {
        ThrowUtils.throwIf(!StringUtils.hasText(url), 400, "表情地址不能为空");
        EmojiSticker existing = emojiStickerMapper.selectOne(
            new LambdaQueryWrapper<EmojiSticker>()
                .eq(EmojiSticker::getUserId, userId)
                .eq(EmojiSticker::getUrl, url)
                .last("LIMIT 1")
        );
        if (existing != null) {
            return existing;
        }
        EmojiSticker sticker = new EmojiSticker();
        sticker.setUserId(userId);
        sticker.setUrl(url);
        emojiStickerMapper.insert(sticker);
        return sticker;
    }

    /**
     * 删除收藏，校验归属（防止越权删除他人表情）。
     */
    public void delete(Long userId, Long id) {
        ThrowUtils.throwIf(id == null, 400, "id 不能为空");
        EmojiSticker sticker = emojiStickerMapper.selectById(id);
        ThrowUtils.throwIf(sticker == null, 404, "表情不存在");
        ThrowUtils.throwIf(!sticker.getUserId().equals(userId), 403, "无权删除该表情");
        emojiStickerMapper.deleteById(id);
    }
}
