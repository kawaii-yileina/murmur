package com.im.auth.controller;

import com.im.auth.crypto.EncryptedResponse;
import com.im.common.model.Result;
import com.im.core.search.dto.SearchResponse;
import com.im.core.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 聚合搜索：返回消息内容，响应走 REST 内容加密。
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @EncryptedResponse
    @GetMapping
    public Result<SearchResponse> search(
            @RequestAttribute("userId") String userId,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String types,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) Long groupId) {
        return Result.ok(searchService.search(Long.parseLong(userId), keyword, types, limit, groupId));
    }
}
