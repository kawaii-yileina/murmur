package com.im.core.search.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.common.exception.BizException;
import com.im.core.group.entity.GroupMember;
import com.im.core.group.mapper.GroupMemberMapper;
import com.im.core.search.dto.ContactRow;
import com.im.core.search.dto.GroupRow;
import com.im.core.search.dto.MemberRow;
import com.im.core.search.dto.MessageRow;
import com.im.core.search.dto.SearchResponse;
import com.im.core.search.mapper.SearchMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 聚合搜索：联系人、群聊、群成员、聊天消息。
 * 关键词只做参数化 LIKE（转义 %/_），消息 v1 仅 TEXT。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private static final int MAX_LIMIT = 50;
    private static final long MESSAGE_TOTAL_CAP = 99;
    private static final int MAX_SEARCHES_PER_SECOND = 10;
    private static final Set<String> ALL_TYPES = Set.of("contact", "group", "member", "message");

    private final SearchMapper searchMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final StringRedisTemplate redisTemplate;

    public SearchResponse search(Long me, String keyword, String types, int limit, Long groupId) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty() || kw.length() > 50) {
            throw new BizException(400, "keyword length must be 1-50");
        }
        checkRate(me);
        int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
        Set<String> selected = parseTypes(types);
        String like = "%" + escapeLike(kw) + "%";

        if (groupId != null) {
            boolean member = groupMemberMapper.selectCount(new LambdaQueryWrapper<GroupMember>()
                    .eq(GroupMember::getGroupId, groupId)
                    .eq(GroupMember::getUserId, me)) > 0;
            if (!member) {
                throw new BizException(403, "No permission to search this group");
            }
            if (!selected.contains("message")) {
                return emptyResponse();
            }
            return new SearchResponse(emptyCategory(), emptyCategory(), emptyCategory(),
                    searchMessages(me, like, size, groupId));
        }

        return new SearchResponse(
                selected.contains("contact") ? searchContacts(me, like, size) : emptyCategory(),
                selected.contains("group") ? searchGroups(me, like, size) : emptyCategory(),
                selected.contains("member") ? searchMembers(me, like, size) : emptyCategory(),
                selected.contains("message") ? searchMessages(me, like, size, null) : emptyCategory());
    }

    private SearchResponse.Category<SearchResponse.ContactItem> searchContacts(Long me, String like, int limit) {
        long total = searchMapper.countContacts(me, like);
        List<SearchResponse.ContactItem> items = searchMapper.searchContacts(me, like, limit).stream()
                .map(this::toContact)
                .toList();
        return new SearchResponse.Category<>(total, items);
    }

    private SearchResponse.Category<SearchResponse.GroupItem> searchGroups(Long me, String like, int limit) {
        long total = searchMapper.countGroups(me, like);
        List<SearchResponse.GroupItem> items = searchMapper.searchGroups(me, like, limit).stream()
                .map(this::toGroup)
                .toList();
        return new SearchResponse.Category<>(total, items);
    }

    private SearchResponse.Category<SearchResponse.MemberItem> searchMembers(Long me, String like, int limit) {
        long total = searchMapper.countMembers(me, like);
        List<SearchResponse.MemberItem> items = searchMapper.searchMembers(me, like, limit).stream()
                .map(this::toMember)
                .toList();
        return new SearchResponse.Category<>(total, items);
    }

    private SearchResponse.Category<SearchResponse.MessageItem> searchMessages(
            Long me, String like, int limit, Long groupId) {
        long total;
        List<MessageRow> rows = new ArrayList<>();
        if (groupId != null) {
            total = searchMapper.countGroupMessages(me, like, groupId);
            rows.addAll(searchMapper.searchGroupMessages(me, like, groupId, limit));
        } else {
            total = searchMapper.countPrivateMessages(me, like)
                    + searchMapper.countGroupMessages(me, like, null);
            rows.addAll(searchMapper.searchPrivateMessages(me, like, limit));
            rows.addAll(searchMapper.searchGroupMessages(me, like, null, limit));
        }

        List<SearchResponse.MessageItem> items = rows.stream()
                .sorted(Comparator.comparing(MessageRow::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .limit(limit)
                .map(this::toMessage)
                .toList();
        return new SearchResponse.Category<>(Math.min(total, MESSAGE_TOTAL_CAP), items);
    }

    private SearchResponse.ContactItem toContact(ContactRow row) {
        return new SearchResponse.ContactItem(row.getUserId(), row.getUsername(), row.getNickname(),
                row.getAvatar(), row.getSignature(), row.getIsFriend());
    }

    private SearchResponse.GroupItem toGroup(GroupRow row) {
        return new SearchResponse.GroupItem(row.getId(), row.getName(), row.getAvatar(), row.getOwnerId());
    }

    private SearchResponse.MemberItem toMember(MemberRow row) {
        return new SearchResponse.MemberItem(row.getUserId(), row.getUsername(), row.getNickname(),
                row.getAvatar(), row.getGroupId(), row.getGroupName(), row.getRole());
    }

    private SearchResponse.MessageItem toMessage(MessageRow row) {
        return new SearchResponse.MessageItem(row.getMsgId(), row.getSeq(), row.getTargetId(),
                row.getChatType(), row.getSenderId(), row.getSenderName(), row.getGroupName(),
                row.getContent(), row.getCreatedAt());
    }

    private static Set<String> parseTypes(String types) {
        if (types == null || types.isBlank()) {
            return ALL_TYPES;
        }
        Set<String> selected = new LinkedHashSet<>();
        Arrays.stream(types.split(","))
                .map(String::trim)
                .map(SearchService::normalizeType)
                .filter(ALL_TYPES::contains)
                .forEach(selected::add);
        return selected;
    }

    private static String normalizeType(String type) {
        return switch (type) {
            case "contacts" -> "contact";
            case "groups" -> "group";
            case "members" -> "member";
            case "messages" -> "message";
            default -> type;
        };
    }

    /** 用户级滑动窗口限流（1 秒 10 次），避免搜索被用于批量扫库 */
    private void checkRate(Long userId) {
        String key = "im:search:rate:" + userId;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            redisTemplate.expire(key, Duration.ofSeconds(1));
            if (count != null && count > MAX_SEARCHES_PER_SECOND) {
                throw new BizException(429, "Too many searches");
            }
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            // Redis 故障不阻断搜索（限流是保护措施，不是业务前置条件）
            log.warn("Search rate limit unavailable: {}", e.getMessage());
        }
    }

    /** MySQL LIKE 默认反斜杠转义：把用户输入中的通配符转义掉 */
    private static String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static <T> SearchResponse.Category<T> emptyCategory() {
        return new SearchResponse.Category<>(0, List.of());
    }

    private static SearchResponse emptyResponse() {
        return new SearchResponse(emptyCategory(), emptyCategory(), emptyCategory(), emptyCategory());
    }
}
