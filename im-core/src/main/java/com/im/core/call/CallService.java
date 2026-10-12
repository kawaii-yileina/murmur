package com.im.core.call;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.elaina.chat.core.message.ChatMessage;
import com.im.common.constant.ChatConstants;
import com.im.core.friend.entity.Friend;
import com.im.core.friend.mapper.FriendMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 通话信令状态服务：在 Redis 中暂存进行中的通话、忙线租约与振铃超时队列。
 * 仅依赖 Redis 与 FriendMapper，不依赖 MessageGateway（位于框架拦截器链路上，禁止成环）。
 *
 * Key：
 * - im:call:{callId}        Hash{callerId,calleeId,media,status,createdAt}(TTL 3600s，ACTIVE 期间续期)
 * - im:call:busy:{userId}   String=callId（与通话同寿命，随状态更新续期）
 * - im:call:pending         ZSet{callId -> 振铃截止时间ms}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallService {

    public enum Result { OK, BUSY, NOT_FRIEND, UNKNOWN }

    public static final String STATUS_CALLING = "CALLING";
    public static final String STATUS_RINGING = "RINGING";
    public static final String STATUS_ACTIVE = "ACTIVE";

    private static final String CALL_KEY = "im:call:";
    private static final String BUSY_KEY = "im:call:busy:";
    private static final String PENDING_KEY = "im:call:pending";
    private static final String ACTIVE_KEY = "im:call:active";
    /** 终态标记（单点发布结束事件，避免双方同时结束重复发布） */
    private static final String ENDED_KEY = "im:call:ended:";
    /** 通话状态/忙线 TTL：ACTIVE 期间由定时续期维持，避免长通话过期 */
    private static final long CALL_TTL_SECONDS = 3600;
    /** ACTIVE 通话最大寿命（续期上限），超过视为异常残留并清理 */
    private static final long CALL_MAX_SECONDS = 6 * 3600;

    /** 值匹配才删除（避免旧通话清理掉新通话的忙线） */
    private static final RedisScript<Long> DEL_IF_MATCH = RedisScript.of(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    /** 值匹配才续期 */
    private static final RedisScript<Long> EXPIRE_IF_MATCH = RedisScript.of(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('EXPIRE', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    /**
     * 接听 CAS（原子）：仅当当前状态不是 ACTIVE 时置为 ACTIVE 并写 answeredAt。
     * 避免多设备同毫秒重复接听各自覆写 answeredAt/重复通知。返回 1=本次完成状态迁移。
     */
    private static final RedisScript<Long> ACCEPT_CAS = RedisScript.of(
            "local st = redis.call('HGET', KEYS[1], 'status') "
            + "if not st then return 0 end "
            + "if st == 'ACTIVE' then return 0 end "
            + "redis.call('HSET', KEYS[1], 'status', ARGV[1]) "
            + "redis.call('HSET', KEYS[1], 'answeredAt', ARGV[2]) "
            + "redis.call('EXPIRE', KEYS[1], ARGV[3]) "
            + "return 1",
            Long.class);

    /**
     * 超时终结（原子）：仅当状态非 ACTIVE 才清理（避免与"刚接听"竞态拆掉通话）。
     * KEYS[1]=call hash KEYS[2]=busy:caller KEYS[3]=busy:callee ARGV[1]=callId
     * 返回 1=已终结，0=已是 ACTIVE 未处理。
     */
    private static final RedisScript<Long> TIMEOUT_TERMINATE = RedisScript.of(
            "local st = redis.call('HGET', KEYS[1], 'status') "
            + "if not st then return 0 end "
            + "if st == 'ACTIVE' then return 0 end "
            + "if redis.call('GET', KEYS[2]) == ARGV[1] then redis.call('DEL', KEYS[2]) end "
            + "if redis.call('GET', KEYS[3]) == ARGV[1] then redis.call('DEL', KEYS[3]) end "
            + "redis.call('DEL', KEYS[1]) "
            + "return 1",
            Long.class);

    private final StringRedisTemplate redis;
    private final FriendMapper friendMapper;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Value("${im.call.ring-timeout-seconds:30}")
    private long ringTimeoutSeconds;

    /**
     * 主叫发起呼叫：原子占用双方忙线租约、校验好友，登记通话状态与超时队列。
     * callId 已存在（复用/伪造）或任一方忙线 → 返回 BUSY/UNKNOWN。
     */
    public Result onInvite(ChatMessage msg) {
        String callId = header(msg, ChatConstants.HEADER_CALL_ID);
        String caller = msg.getFrom();
        String callee = msg.getTo();
        if (isBlank(callId) || isBlank(caller) || isBlank(callee)) {
            return Result.UNKNOWN;
        }
        // 防止复用 callId 覆盖既有通话状态
        if (Boolean.TRUE.equals(redis.hasKey(CALL_KEY + callId))) {
            return Result.UNKNOWN;
        }
        // 原子占用主叫忙线（未在通话中的用户才能发起）
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(BUSY_KEY + caller, callId, CALL_TTL_SECONDS, TimeUnit.SECONDS))) {
            return Result.BUSY;
        }
        // 原子占用被叫忙线
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(BUSY_KEY + callee, callId, CALL_TTL_SECONDS, TimeUnit.SECONDS))) {
            deleteIfMatch(BUSY_KEY + caller, callId);
            return Result.BUSY;
        }
        if (!isFriend(caller, callee)) {
            deleteIfMatch(BUSY_KEY + caller, callId);
            deleteIfMatch(BUSY_KEY + callee, callId);
            return Result.NOT_FRIEND;
        }

        String media = header(msg, ChatConstants.HEADER_CALL_MEDIA);
        long now = System.currentTimeMillis();
        Map<String, String> state = new HashMap<>();
        state.put("callerId", caller);
        state.put("calleeId", callee);
        state.put("media", isBlank(media) ? "audio" : media);
        state.put("status", STATUS_CALLING);
        state.put("createdAt", String.valueOf(now));
        redis.opsForHash().putAll(CALL_KEY + callId, state);
        redis.expire(CALL_KEY + callId, CALL_TTL_SECONDS, TimeUnit.SECONDS);
        redis.opsForZSet().add(PENDING_KEY, callId, now + ringTimeoutSeconds * 1000);
        return Result.OK;
    }

    /** 被叫振铃 */
    public void onRinging(String callId) {
        updateStatus(callId, STATUS_RINGING);
    }

    /** 被叫接听：原子 CAS 置 ACTIVE（重复接听只生效一次），移出超时队列、加入续期集合 */
    public void onAccept(String callId) {
        if (isBlank(callId)) {
            return;
        }
        Long r = redis.execute(ACCEPT_CAS, List.of(CALL_KEY + callId),
                STATUS_ACTIVE, String.valueOf(System.currentTimeMillis()), String.valueOf(CALL_TTL_SECONDS));
        if (r != null && r == 1L) {
            renewBusy(callId, str(redis.opsForHash().get(CALL_KEY + callId, "callerId")),
                    str(redis.opsForHash().get(CALL_KEY + callId, "calleeId")));
            redis.opsForZSet().remove(PENDING_KEY, callId);
            redis.opsForZSet().add(ACTIVE_KEY, callId, System.currentTimeMillis());
        }
    }

    /** 续期当前通话状态与忙线（供 OFFER/ANSWER/ICE 等后续信令及定时任务调用） */
    public void touch(String callId) {
        if (isBlank(callId) || !Boolean.TRUE.equals(redis.hasKey(CALL_KEY + callId))) {
            return;
        }
        redis.expire(CALL_KEY + callId, CALL_TTL_SECONDS, TimeUnit.SECONDS);
        renewBusy(callId, str(redis.opsForHash().get(CALL_KEY + callId, "callerId")),
                str(redis.opsForHash().get(CALL_KEY + callId, "calleeId")));
        redis.opsForZSet().add(ACTIVE_KEY, callId, System.currentTimeMillis());
    }

    /** 续期全部 ACTIVE 通话（长通话保活）；超过最大寿命或状态已消失则清理 */
    public void renewActiveCalls() {
        Set<String> active = redis.opsForZSet().range(ACTIVE_KEY, 0, -1);
        if (active == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String callId : active) {
            try {
                Map<Object, Object> state = redis.opsForHash().entries(CALL_KEY + callId);
                if (state == null || state.isEmpty()) {
                    redis.opsForZSet().remove(ACTIVE_KEY, callId);
                    continue;
                }
                long created = parseLong(state.get("createdAt"), now);
                if (now - created > CALL_MAX_SECONDS * 1000) {
                    terminate(callId);
                    continue;
                }
                if (STATUS_ACTIVE.equals(state.get("status"))) {
                    touch(callId);
                } else {
                    redis.opsForZSet().remove(ACTIVE_KEY, callId);
                }
            } catch (Exception e) {
                log.debug("Renew call {} failed: {}", callId, e.getMessage());
            }
        }
    }

    /** 通话结束：单点发布结束事件（落通话记录 + 下发 CALL 条目）并按 callId 归属清理状态与忙线 */
    public void terminate(String callId, String status) {
        if (isBlank(callId)) {
            return;
        }
        Map<Object, Object> state = redis.opsForHash().entries(CALL_KEY + callId);
        if (state != null && !state.isEmpty()) {
            boolean first = claimEnded(callId);
            if (first && !isBlank(status)) {
                publishCallEnded(callId, state, status);
            }
        }
        cleanup(callId, state);
    }

    /** 后备清理（异常残留等）：无有效状态时不发布事件 */
    public void terminate(String callId) {
        terminate(callId, com.im.core.call.entity.CallSession.STATUS_FAILED);
    }

    /** 抢占终态发布权（60s 内仅一次） */
    private boolean claimEnded(String callId) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                ENDED_KEY + callId, "1", 60, TimeUnit.SECONDS));
    }

    private void cleanup(String callId, Map<Object, Object> state) {
        Object caller = state == null ? null : state.get("callerId");
        Object callee = state == null ? null : state.get("calleeId");
        if (caller != null) {
            deleteIfMatch(BUSY_KEY + caller, callId);
        }
        if (callee != null) {
            deleteIfMatch(BUSY_KEY + callee, callId);
        }
        redis.delete(CALL_KEY + callId);
        redis.opsForZSet().remove(PENDING_KEY, callId);
        redis.opsForZSet().remove(ACTIVE_KEY, callId);
    }

    private void publishCallEnded(String callId, Map<Object, Object> state, String status) {
        String caller = str(state.get("callerId"));
        String callee = str(state.get("calleeId"));
        String media = str(state.get("media"));
        long created = parseLong(state.get("createdAt"), System.currentTimeMillis());
        long answered = parseLong(state.get("answeredAt"), 0L);
        long now = System.currentTimeMillis();
        long duration = answered > 0 ? Math.max(0L, (now - answered) / 1000) : 0L;
        eventPublisher.publishEvent(new CallEndedEvent(callId, caller, callee, media, status, duration, created, answered, now));
    }

    private long parseLong(Object v, long def) {
        if (v == null) {
            return def;
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * 超时终结：读状态→非 ACTIVE 才原子清理；清理成功则发布 MISSED 结束事件。
     * 返回 true 表示本次已终结（调度器据此通知双方）。消除"刚接听即被超时拆掉"的竞态。
     */
    public boolean finishTimeout(String callId) {
        if (isBlank(callId)) {
            return false;
        }
        Map<Object, Object> state = redis.opsForHash().entries(CALL_KEY + callId);
        if (state == null || state.isEmpty() || STATUS_ACTIVE.equals(state.get("status"))) {
            return false;
        }
        String caller = str(state.get("callerId"));
        String callee = str(state.get("calleeId"));
        Long r = redis.execute(TIMEOUT_TERMINATE,
                List.of(CALL_KEY + callId, BUSY_KEY + caller, BUSY_KEY + callee), callId);
        if (r != null && r == 1L) {
            if (claimEnded(callId)) {
                publishCallEnded(callId, state, com.im.core.call.entity.CallSession.STATUS_MISSED);
            }
            redis.opsForZSet().remove(PENDING_KEY, callId);
            redis.opsForZSet().remove(ACTIVE_KEY, callId);
            return true;
        }
        return false;
    }

    /** 校验 userId 是否为该通话的主叫或被叫 */
    public boolean isParticipant(String callId, String userId) {
        if (isBlank(callId) || isBlank(userId)) {
            return false;
        }
        Map<Object, Object> state = redis.opsForHash().entries(CALL_KEY + callId);
        return userId.equals(str(state.get("callerId"))) || userId.equals(str(state.get("calleeId")));
    }

    /** 用户当前进行中的通话 callId（无则 null）；忙线索引与通话同寿命 */
    public String callOf(String userId) {
        if (isBlank(userId)) {
            return null;
        }
        String callId = redis.opsForValue().get(BUSY_KEY + userId);
        return isBlank(callId) ? null : callId;
    }

    /** 领取一次超时处理权（ZSet 原子移除成功者才处理），避免多节点重复推送 */
    public boolean claimTimeout(String callId) {
        Long removed = redis.opsForZSet().remove(PENDING_KEY, callId);
        return removed != null && removed > 0;
    }

    /** 取出到期的振铃（调用方需 claimTimeout 领取后处理） */
    public List<String> dueCallIds(long nowMillis) {
        Set<String> members = redis.opsForZSet().rangeByScore(PENDING_KEY, 0, nowMillis);
        return members == null ? List.of() : List.copyOf(members);
    }

    /** 校验 userId 是否为该通话的被叫 */
    public boolean isCallee(String callId, String userId) {
        if (isBlank(callId) || isBlank(userId)) {
            return false;
        }
        Object callee = redis.opsForHash().get(CALL_KEY + callId, "calleeId");
        return userId.equals(str(callee));
    }

    /** 当前通话状态（无则 null） */
    public String statusOf(String callId) {
        Object st = redis.opsForHash().get(CALL_KEY + callId, "status");
        return st == null ? null : String.valueOf(st);
    }

    /** 通话当前状态 Hash（无记录返回空 Map） */
    public Map<Object, Object> state(String callId) {
        return redis.opsForHash().entries(CALL_KEY + callId);
    }

    private boolean updateStatus(String callId, String status) {
        if (isBlank(callId) || !Boolean.TRUE.equals(redis.hasKey(CALL_KEY + callId))) {
            return false;
        }
        redis.opsForHash().put(CALL_KEY + callId, "status", status);
        redis.expire(CALL_KEY + callId, CALL_TTL_SECONDS, TimeUnit.SECONDS);
        renewBusy(callId, str(redis.opsForHash().get(CALL_KEY + callId, "callerId")),
                str(redis.opsForHash().get(CALL_KEY + callId, "calleeId")));
        return true;
    }

    private void renewBusy(String callId, String caller, String callee) {
        if (caller != null) {
            redis.execute(EXPIRE_IF_MATCH, List.of(BUSY_KEY + caller), callId, String.valueOf(CALL_TTL_SECONDS));
        }
        if (callee != null) {
            redis.execute(EXPIRE_IF_MATCH, List.of(BUSY_KEY + callee), callId, String.valueOf(CALL_TTL_SECONDS));
        }
    }

    private void deleteIfMatch(String busyKey, String callId) {
        redis.execute(DEL_IF_MATCH, List.of(busyKey), callId);
    }

    private boolean isFriend(String caller, String callee) {
        try {
            Long callerId = Long.parseLong(caller);
            Long calleeId = Long.parseLong(callee);
            return friendMapper.selectCount(new LambdaQueryWrapper<Friend>()
                    .eq(Friend::getUserId, callerId)
                    .eq(Friend::getFriendId, calleeId)) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String header(ChatMessage msg, String key) {
        if (msg.getHeaders() == null) {
            return null;
        }
        Object value = msg.getHeaders().get(key);
        return value != null ? String.valueOf(value) : null;
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
