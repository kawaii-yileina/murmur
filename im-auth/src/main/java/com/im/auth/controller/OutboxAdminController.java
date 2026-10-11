package com.im.auth.controller;

import com.im.common.model.Result;
import com.im.core.outbox.entity.Outbox;
import com.im.core.outbox.service.OutboxService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Outbox 死信处置接口（运维用）。
 *
 * 仅在 im.cluster.outbox-admin.enabled=true 时装配；且仅允许 im.admin.user-ids 名单内的用户访问
 * （未配置则一律拒绝）。先经框架 JWT 过滤器鉴权（未认证 401），再校验用户名单（非名单返回 403）。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/outbox")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "im.cluster.outbox-admin.enabled", havingValue = "true")
public class OutboxAdminController {

    private final OutboxService outboxService;

    @Value("${im.admin.user-ids:}")
    private String adminUserIds;

    private Set<String> adminSet = Set.of();

    @PostConstruct
    void init() {
        if (adminUserIds != null && !adminUserIds.isBlank()) {
            adminSet = Arrays.stream(adminUserIds.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
        }
    }

    @GetMapping("/dead")
    public ResponseEntity<Result<List<DeadOutboxView>>> listDead(@RequestAttribute("userId") String userId,
                                                                 @RequestParam(defaultValue = "50") int limit) {
        if (!adminSet.contains(userId)) {
            return forbidden(userId, "list");
        }
        if (limit < 1) {
            return ResponseEntity.badRequest().body(Result.error(400, "limit 必须大于 0"));
        }
        return ResponseEntity.ok(Result.ok(outboxService.listDead(limit).stream().map(DeadOutboxView::of).toList()));
    }

    /** 死信重投：重置为待处理，由投递器重新发送 */
    @PostMapping("/dead/{id}/retry")
    public ResponseEntity<Result<Boolean>> retry(@RequestAttribute("userId") String userId, @PathVariable Long id) {
        if (!adminSet.contains(userId)) {
            return forbidden(userId, "retry");
        }
        boolean ok = outboxService.retryDead(id);
        log.info("Outbox dead-letter retry by {}: id={}, affected={}", userId, id, ok);
        return ResponseEntity.ok(Result.ok(ok));
    }

    /** 死信丢弃：彻底删除该任务（不再投递） */
    @PostMapping("/dead/{id}/discard")
    public ResponseEntity<Result<Boolean>> discard(@RequestAttribute("userId") String userId, @PathVariable Long id) {
        if (!adminSet.contains(userId)) {
            return forbidden(userId, "discard");
        }
        boolean ok = outboxService.discardDead(id);
        log.info("Outbox dead-letter discard by {}: id={}, affected={}", userId, id, ok);
        return ResponseEntity.ok(Result.ok(ok));
    }

    private <T> ResponseEntity<Result<T>> forbidden(String userId, String action) {
        log.warn("Outbox admin forbidden: user={}, action={}", userId, action);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.error(403, "无管理员权限"));
    }

    /** 对外视图：不暴露 payload（含消息体）与 lastError（异常信息可能含消息片段） */
    public record DeadOutboxView(Long id, String msgId, String conversationId, Long seq, Integer chatType,
                                 Integer retryCount, LocalDateTime createdAt, LocalDateTime updatedAt) {
        static DeadOutboxView of(Outbox o) {
            return new DeadOutboxView(o.getId(), o.getMsgId(), o.getConversationId(), o.getSeq(),
                    o.getChatType(), o.getRetryCount(), o.getCreatedAt(), o.getUpdatedAt());
        }
    }
}
