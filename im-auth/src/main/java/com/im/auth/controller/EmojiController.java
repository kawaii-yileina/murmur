package com.im.auth.controller;

import com.im.common.model.Result;
import com.im.core.emoji.entity.EmojiSticker;
import com.im.core.emoji.service.EmojiStickerService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 表情包收藏接口。
 */
@RestController
@RequestMapping("/api/emoji/stickers")
@RequiredArgsConstructor
public class EmojiController {

    private final EmojiStickerService emojiStickerService;

    @GetMapping
    public Result<List<EmojiSticker>> list(@RequestAttribute("userId") String userId) {
        return Result.ok(emojiStickerService.list(Long.parseLong(userId)));
    }

    @PostMapping
    public Result<EmojiSticker> add(@RequestAttribute("userId") String userId,
                                    @RequestBody AddStickerRequest request) {
        return Result.ok(emojiStickerService.add(Long.parseLong(userId), request.getUrl()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestAttribute("userId") String userId,
                               @PathVariable Long id) {
        emojiStickerService.delete(Long.parseLong(userId), id);
        return Result.ok();
    }

    @Data
    public static class AddStickerRequest {
        private String url;
    }
}
