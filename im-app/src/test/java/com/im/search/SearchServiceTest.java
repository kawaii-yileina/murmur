package com.im.search;

import com.im.common.exception.BizException;
import com.im.core.group.mapper.GroupMemberMapper;
import com.im.core.search.dto.MessageRow;
import com.im.core.search.dto.SearchResponse;
import com.im.core.search.mapper.SearchMapper;
import com.im.core.search.service.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchServiceTest {

    private static final Long ME = 1L;

    private SearchMapper searchMapper;
    private GroupMemberMapper groupMemberMapper;
    private SearchService service;

    @BeforeEach
    void setUp() {
        searchMapper = mock(SearchMapper.class);
        groupMemberMapper = mock(GroupMemberMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);
        service = new SearchService(searchMapper, groupMemberMapper, redisTemplate);
    }

    @Test
    void rejectsBlankAndTooLongKeyword() {
        assertThatThrownBy(() -> service.search(ME, "  ", "", 20, null))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(400));
        assertThatThrownBy(() -> service.search(ME, "x".repeat(51), "", 20, null))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(400));
    }

    @Test
    void groupSearchRequiresMembership() {
        when(groupMemberMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.search(ME, "hello", "message", 20, 9L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        verify(searchMapper, never()).searchGroupMessages(anyLong(), anyString(), any(), anyInt());
    }

    @Test
    void typesFilterOnlyQueriesRequestedCategory() {
        when(searchMapper.countContacts(anyLong(), anyString())).thenReturn(1L);
        when(searchMapper.searchContacts(anyLong(), anyString(), anyInt())).thenReturn(List.of());

        SearchResponse result = service.search(ME, "alice", "contact", 20, null);

        assertThat(result.contacts().total()).isEqualTo(1);
        assertThat(result.groups().total()).isZero();
        assertThat(result.members().total()).isZero();
        assertThat(result.messages().total()).isZero();
        verify(searchMapper).countContacts(anyLong(), anyString());
        verify(searchMapper, never()).countGroups(anyLong(), anyString());
        verify(searchMapper, never()).countMembers(anyLong(), anyString());
        verify(searchMapper, never()).countPrivateMessages(anyLong(), anyString());
    }

    @Test
    void escapesLikeWildcards() {
        ArgumentCaptor<String> like = ArgumentCaptor.forClass(String.class);
        when(searchMapper.countContacts(anyLong(), anyString())).thenReturn(0L);
        when(searchMapper.searchContacts(anyLong(), like.capture(), anyInt())).thenReturn(List.of());

        service.search(ME, "50%_x", "contact", 20, null);

        assertThat(like.getValue()).isEqualTo("%50\\%\\_x%");
    }

    @Test
    void mergesAndSortsMessagesByTimeAndCapsTotal() {
        MessageRow old = message("m1", 1L, LocalDateTime.now().minusMinutes(2));
        MessageRow newer = message("m2", 2L, LocalDateTime.now());
        when(searchMapper.countPrivateMessages(anyLong(), anyString())).thenReturn(80L);
        when(searchMapper.countGroupMessages(anyLong(), anyString(), any())).thenReturn(40L);
        when(searchMapper.searchPrivateMessages(anyLong(), anyString(), anyInt())).thenReturn(List.of(old));
        when(searchMapper.searchGroupMessages(anyLong(), anyString(), any(), anyInt())).thenReturn(List.of(newer));

        SearchResponse result = service.search(ME, "msg", "message", 20, null);

        assertThat(result.messages().total()).isEqualTo(99);
        assertThat(result.messages().items()).extracting(SearchResponse.MessageItem::msgId)
                .containsExactly("m2", "m1");
    }

    @Test
    void groupScopeReturnsOnlyMessages() {
        when(groupMemberMapper.selectCount(any())).thenReturn(1L);
        when(searchMapper.countGroupMessages(anyLong(), anyString(), any())).thenReturn(1L);
        when(searchMapper.searchGroupMessages(anyLong(), anyString(), any(), anyInt()))
                .thenReturn(List.of(message("g1", 3L, LocalDateTime.now())));

        SearchResponse result = service.search(ME, "hello", "", 20, 9L);

        assertThat(result.contacts().items()).isEmpty();
        assertThat(result.groups().items()).isEmpty();
        assertThat(result.members().items()).isEmpty();
        assertThat(result.messages().items()).hasSize(1);
        verify(searchMapper, never()).countPrivateMessages(anyLong(), anyString());
    }

    private static MessageRow message(String msgId, long seq, LocalDateTime createdAt) {
        MessageRow row = new MessageRow();
        row.setMsgId(msgId);
        row.setSeq(seq);
        row.setTargetId(2L);
        row.setChatType(0);
        row.setSenderId(1L);
        row.setSenderName("N-A");
        row.setContent("hello");
        row.setCreatedAt(createdAt);
        return row;
    }
}
