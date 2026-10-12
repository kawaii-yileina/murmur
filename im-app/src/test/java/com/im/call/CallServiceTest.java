package com.im.call;

import com.elaina.chat.core.message.ChatMessage;
import com.im.common.constant.ChatConstants;
import com.im.core.call.CallEndedEvent;
import com.im.core.call.CallService;
import com.im.core.call.entity.CallSession;
import com.im.core.friend.mapper.FriendMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CallService 状态机单测（Mockito，不依赖 Redis）。
 * 覆盖：忙线占用、接听 CAS、振铃 CAS、终态单点发布、超时终结、异常残留不发布。
 */
class CallServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private HashOperations<String, Object, Object> hashOps;
    private ZSetOperations<String, String> zsetOps;
    private FriendMapper friendMapper;
    private ApplicationEventPublisher publisher;
    private CallService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        hashOps = mock(HashOperations.class);
        zsetOps = mock(ZSetOperations.class);
        friendMapper = mock(FriendMapper.class);
        publisher = mock(ApplicationEventPublisher.class);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(redis.opsForHash()).thenReturn(hashOps);
        lenient().when(redis.opsForZSet()).thenReturn(zsetOps);
        service = new CallService(redis, friendMapper, publisher);
        ReflectionTestUtils.setField(service, "ringTimeoutSeconds", 30L);
    }

    private void stubScript(Long result) {
        lenient().when(redis.execute(any(), anyList(), any(Object[].class))).thenReturn(result);
    }

    private Map<Object, Object> state(String status) {
        Map<Object, Object> m = new HashMap<>();
        m.put("callerId", "1");
        m.put("calleeId", "2");
        m.put("media", "audio");
        m.put("status", status);
        m.put("createdAt", String.valueOf(System.currentTimeMillis()));
        return m;
    }

    private ChatMessage invite() {
        ChatMessage m = ChatMessage.builder()
                .id("c1").type(ChatConstants.CALL_INVITE).from("1").to("2").group(false).content("").build();
        m.addHeader(ChatConstants.HEADER_CALL_ID, "call-1");
        m.addHeader(ChatConstants.HEADER_CALL_MEDIA, "audio");
        return m;
    }

    @Test
    void onInvite_busyWhenCallerAlreadyInCall() {
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        assertThat(service.onInvite(invite())).isEqualTo(CallService.Result.BUSY);
    }

    @Test
    void onInvite_notFriendWhenRelationMissing() {
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(friendMapper.selectCount(any())).thenReturn(0L);
        assertThat(service.onInvite(invite())).isEqualTo(CallService.Result.NOT_FRIEND);
        // 非好友：释放主叫与被叫双方忙线
        verify(redis, times(2)).execute(any(), anyList(), any(Object[].class));
    }

    @Test
    void onInvite_busyWhenCalleeAlreadyInCall() {
        // 主叫占用成功、被叫占用失败
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true, false);
        assertThat(service.onInvite(invite())).isEqualTo(CallService.Result.BUSY);
        // 仅释放主叫忙线一次
        verify(redis, times(1)).execute(any(), anyList(), any(Object[].class));
    }

    @Test
    void onAccept_appliesWhenCasWins() {
        stubScript(1L);
        service.onAccept("call-1");
        verify(zsetOps).remove(anyString(), any());
        verify(zsetOps, atLeastOnce()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void onAccept_ignoredWhenCasLoses() {
        stubScript(0L);
        service.onAccept("call-1");
        verify(zsetOps, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void onRinging_casLosesRunsScriptOnce() {
        stubScript(0L);
        service.onRinging("call-1");
        // 失败分支只执行 RING_CAS 一次，且不读取忙线成员（不续期）
        verify(redis, times(1)).execute(any(), anyList(), any(Object[].class));
        verify(hashOps, never()).get(anyString(), any());
    }

    @Test
    void onRinging_casWinsRenewsBusy() {
        stubScript(1L);
        when(hashOps.get(anyString(), any())).thenReturn("1");
        service.onRinging("call-1");
        // RING_CAS 一次 + renewBusy（主叫/被叫各一次 EXPIRE_IF_MATCH）
        verify(redis, times(3)).execute(any(), anyList(), any(Object[].class));
    }

    @Test
    void terminate_publishesOnceWhenClaimWins() {
        when(hashOps.entries(anyString())).thenReturn(state(CallService.STATUS_ACTIVE));
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        service.terminate("call-1", CallSession.STATUS_COMPLETED);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(CallEndedEvent.class);
        assertThat(((CallEndedEvent) captor.getValue()).status()).isEqualTo(CallSession.STATUS_COMPLETED);
    }

    @Test
    void terminate_doesNotPublishWhenStateEmpty() {
        when(hashOps.entries(anyString())).thenReturn(Map.of());
        service.terminate("call-1", CallSession.STATUS_COMPLETED);
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void finishTimeout_publishesMissedWhenLuaWins() {
        when(hashOps.entries(anyString())).thenReturn(state(CallService.STATUS_RINGING));
        stubScript(1L);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        assertThat(service.finishTimeout("call-1")).isTrue();
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(((CallEndedEvent) captor.getValue()).status()).isEqualTo(CallSession.STATUS_MISSED);
    }

    @Test
    void finishTimeout_noopWhenAlreadyActive() {
        when(hashOps.entries(anyString())).thenReturn(state(CallService.STATUS_ACTIVE));
        assertThat(service.finishTimeout("call-1")).isFalse();
        verify(publisher, never()).publishEvent(any());
    }
}
