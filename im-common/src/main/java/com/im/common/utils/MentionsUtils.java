package com.im.common.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.common.constant.ChatConstants;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * @提及信息解析工具。
 *
 * 前端以 JSON 数组形式放在消息 headers.mentions 中：
 * {@code [{"userId":"123","nickname":"张三"}, {"userId":"all","nickname":"所有人"}]}
 */
public final class MentionsUtils {

    private MentionsUtils() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 提取被 @ 的成员 id 集合（含特殊值 all）；解析失败返回空集合 */
    public static Set<String> extractUserIds(String json) {
        Set<String> ids = new LinkedHashSet<>();
        if (json == null || json.isBlank()) return ids;
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isArray()) return ids;
            for (JsonNode node : root) {
                String userId = node.path("userId").asText("");
                if (!userId.isBlank()) {
                    ids.add(userId);
                }
            }
        } catch (Exception ignored) {
            // 非法 JSON 视为无提及
        }
        return ids;
    }

    /** 是否包含 @所有人 */
    public static boolean containsAll(String json) {
        return extractUserIds(json).contains(ChatConstants.MENTION_ALL);
    }
}
