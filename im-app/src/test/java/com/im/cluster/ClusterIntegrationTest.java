package com.im.cluster;

import com.elaina.chat.core.connection.Connection;
import com.elaina.chat.core.message.ChatMessage;
import com.im.common.exception.BizException;
import com.im.core.chat.hook.MessagePersistHook;
import com.im.core.chat.registry.MultiConnectionRegistry;
import com.im.core.cluster.route.RouteRegistry;
import com.im.core.message.dto.MessageSyncResult;
import com.im.core.message.service.MessageService;
import com.im.core.message.service.OfflineMessageService;
import com.im.core.message.util.ConversationIds;
import com.im.core.outbox.service.OutboxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分布式核心逻辑集成测试（单机模式，cluster 关闭）：真实 MySQL + Redis。
 *
 * 前置：本地 docker 提供 MySQL(3308, root/root, im_db) 与 Redis(6380)；
 * 测试使用独立的高位 id 并在结束后清理，避免污染业务数据。
 * 若本机未启动上述服务，则整类自动跳过（不影响 `mvn test`）。
 */
@EnabledIf("infraAvailable")
@SpringBootTest(properties = {
        "server.port=0",
        "chat.port=19100",
        "hermes.enabled=false",
        "im.rate-limit.messages-per-second=0",
        "logging.level.com.im=INFO",
        "logging.level.com.elaina=INFO"
})
class ClusterIntegrationTest {

    private static final long A = 8_200_000_000_000_000_001L;
    private static final long B = 8_200_000_000_000_000_002L;

    /** 集成测试前置：本地 MySQL/Redis 可达才运行，否则跳过（避免无 docker 环境下构建失败） */
    static boolean infraAvailable() {
        return reachable("127.0.0.1", 3308) && reachable("127.0.0.1", 6380);
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Autowired
    private OutboxService outboxService;
    @Autowired
    private MessageService messageService;
    @Autowired
    private OfflineMessageService offlineMessageService;
    @Autowired
    private RouteRegistry routeRegistry;
    @Autowired
    private MessagePersistHook messagePersistHook;
    @Autowired
    private MultiConnectionRegistry connectionRegistry;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM t_message WHERE (sender_id=? AND receiver_id=?) OR (sender_id=? AND receiver_id=?)",
                A, B, B, A);
        jdbc.update("DELETE FROM t_conversation WHERE user_id IN (?,?)", A, B);
        jdbc.update("DELETE FROM t_conversation_seq WHERE conversation_id=?", conversationId(A, B));
        jdbc.update("DELETE FROM t_outbox WHERE conversation_id=?", conversationId(A, B));
        redis.delete("im:offline:" + A);
        redis.delete("im:offline:" + B);
        redis.delete("im:conn:epoch:" + A + ":d-route");
    }

    /** 同一会话并发写入：seq 必须唯一且连续（行锁串行化） */
    @Test
    void concurrentPersist_allocatesUniqueContiguousSeq() throws Exception {
        int n = 30;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                ChatMessage m = text(A, B, "c" + idx);
                return outboxService.persistAndEnqueue(m, List.of(String.valueOf(B)), List.of(String.valueOf(B)));
            }));
        }
        start.countDown();
        Set<Long> seqs = new HashSet<>();
        for (Future<Long> f : futures) {
            seqs.add(f.get(15, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertThat(seqs).hasSize(n);
        long min = Collections.min(seqs);
        long max = Collections.max(seqs);
        assertThat(max - min).isEqualTo(n - 1L);
    }

    /** /sync：按 seq 升序返回、lastSeq 作为游标、无新增时为空 */
    @Test
    void sync_returnsOrderedMessagesWithCursor() {
        long s1 = outboxService.persistAndEnqueue(text(A, B, "m1"), List.of(String.valueOf(B)), List.of(String.valueOf(B)));
        long s2 = outboxService.persistAndEnqueue(text(A, B, "m2"), List.of(String.valueOf(B)), List.of(String.valueOf(B)));
        long s3 = outboxService.persistAndEnqueue(text(A, B, "m3"), List.of(String.valueOf(B)), List.of(String.valueOf(B)));

        MessageSyncResult r = messageService.sync(A, B, 0, s1, 100);
        assertThat(r.getMessages()).extracting("content").containsExactly("m2", "m3");
        assertThat(r.getMessages()).extracting("seq").containsExactly(s2, s3);
        assertThat(r.getLastSeq()).isEqualTo(s3);
        assertThat(r.isHasMore()).isFalse();

        MessageSyncResult next = messageService.sync(A, B, 0, r.getLastSeq(), 100);
        assertThat(next.getMessages()).isEmpty();
        assertThat(next.getLastSeq()).isEqualTo(s3);

        // limit 截断：hasMore=true 且 lastSeq 停在截断处
        MessageSyncResult paged = messageService.sync(A, B, 0, s1, 1);
        assertThat(paged.getMessages()).extracting("content").containsExactly("m2");
        assertThat(paged.isHasMore()).isTrue();
        assertThat(paged.getLastSeq()).isEqualTo(s2);
    }

    /** /sync：群聊非成员越权返回 403 */
    @Test
    void sync_groupNonMember_forbidden() {
        long groupId = 8_200_000_000_000_000_099L;
        long outsider = 8_200_000_000_000_000_009L;
        assertThatThrownBy(() -> messageService.sync(outsider, groupId, 1, 0, 100))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无权访问");
    }

    /** ACK 携带与 t_message 一致的权威 seq 与 serverMsgId */
    @Test
    void ack_carriesAuthoritativeSeqAndServerMsgId() {
        CapturingConnection conn = new CapturingConnection("conn-ack", "d-ack");
        connectionRegistry.register(String.valueOf(A), conn);
        try {
            ChatMessage m = text(A, B, "ack-me");
            String clientMsgId = "cmid-" + UUID.randomUUID();
            m.addHeader("clientMsgId", clientMsgId);
            messagePersistHook.afterSend(m, List.of(String.valueOf(B)), List.of(String.valueOf(B)));

            ChatMessage ack = conn.written.stream()
                    .filter(o -> o instanceof ChatMessage)
                    .map(o -> (ChatMessage) o)
                    .filter(cm -> "true".equals(String.valueOf(cm.getHeader("ack"))))
                    .findFirst().orElseThrow();

            Long dbSeq = jdbc.queryForObject("SELECT seq FROM t_message WHERE msg_id=?", Long.class, m.getId());
            assertThat(ack.getHeader("clientMsgId")).isEqualTo(clientMsgId);
            assertThat(ack.getHeader("serverMsgId")).isEqualTo(m.getId());
            assertThat(String.valueOf(ack.getHeader("seq"))).isEqualTo(String.valueOf(dbSeq));
        } finally {
            connectionRegistry.removeConnection(conn);
        }
    }

    /** 实时消息（无 seq）到达后，/sync 补回同一 msgId 且带 seq，不新增重复条目 */
    @Test
    void sync_backfillsSeqForAlreadyPersistedMessage() {
        ChatMessage m = text(A, B, "race");
        long seq = outboxService.persistAndEnqueue(m, List.of(String.valueOf(B)), List.of(String.valueOf(B)));

        MessageSyncResult r = messageService.sync(A, B, 0, 0, 100);
        assertThat(r.getMessages()).hasSize(1);
        assertThat(r.getMessages().get(0).getMsgId()).isEqualTo(m.getId());
        assertThat(r.getMessages().get(0).getSeq()).isEqualTo(seq);
    }

    /** 离线队列原子写：超过上限仅保留最近 500 条，且设置了 TTL；可原子取出清空 */
    @Test
    void offlineStore_isCappedWithTtlAndDrainable() {
        String key = "im:offline:" + A;
        for (int i = 0; i < 505; i++) {
            offlineMessageService.storeOfflineMessage(String.valueOf(A), text(A, B, "off" + i));
        }
        Long len = redis.opsForList().size(key);
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        assertThat(len).isEqualTo(500L);
        assertThat(ttl).isGreaterThan(0L);

        List<ChatMessage> drained = offlineMessageService.getOfflineMessages(String.valueOf(A));
        assertThat(drained).hasSize(500);
        assertThat(redis.hasKey(key)).isFalse();
    }

    /** 路由所有权：续期/删除需 nodeId + connId 匹配，旧连接的迟到操作不影响新连接 */
    @Test
    void route_renewAndUnregister_requireOwnership() {
        String uid = String.valueOf(A);
        String dev = "d-route";
        routeRegistry.register(uid, dev, "web", "conn-1", "web");
        assertThat(routeRegistry.find(uid, dev)).isNotNull();

        assertThat(routeRegistry.renew(uid, dev, "conn-x")).isFalse();
        assertThat(routeRegistry.renew(uid, dev, "conn-1")).isTrue();

        // 非所有者的下线事件不应删除路由
        routeRegistry.unregister(uid, dev, "conn-x");
        assertThat(routeRegistry.find(uid, dev)).isNotNull();

        routeRegistry.unregister(uid, dev, "conn-1");
        assertThat(routeRegistry.find(uid, dev)).isNull();
    }

    // ---------- helpers ----------

    private static String conversationId(long from, long to) {
        return ConversationIds.of(text(from, to, "x"));
    }

    private static ChatMessage text(long from, long to, String content) {
        return ChatMessage.builder()
                .id(UUID.randomUUID().toString())
                .type("TEXT")
                .from(String.valueOf(from))
                .to(String.valueOf(to))
                .group(false)
                .content(content)
                .build();
    }

    /** 捕获下行写入的假连接，用于断言 ACK 内容 */
    private static final class CapturingConnection implements Connection {
        private final String id;
        private final Map<String, Object> attrs = new HashMap<>();
        private final List<Object> written = new ArrayList<>();

        CapturingConnection(String id, String deviceId) {
            this.id = id;
            attrs.put("deviceId", deviceId);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void write(Object message) {
            written.add(message);
        }

        @Override
        public void close() {
        }

        @Override
        public Map<String, Object> attributes() {
            return attrs;
        }

        @Override
        public boolean isActive() {
            return true;
        }
    }
}
