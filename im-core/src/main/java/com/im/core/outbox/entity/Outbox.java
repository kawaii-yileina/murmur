package com.im.core.outbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Outbox：跨节点可靠投递任务。与消息在同一数据库事务内写入。
 *
 * payload 只保存逻辑接收目标（recipients + 原始消息），实际发送时再按当前路由解析目标节点。
 * status：PENDING / PROCESSING / DONE / DEAD。
 * DONE 仅表示 RocketMQ 发送成功，不代表客户端已送达。
 */
@Data
@TableName("t_outbox")
public class Outbox {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_DEAD = "DEAD";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String msgId;

    private String conversationId;

    private Long seq;

    private Integer chatType;

    /** {recipients:[userId...], message:{ChatMessage JSON}} */
    private String payload;

    private String status;

    private Integer retryCount;

    private LocalDateTime leaseUntil;

    private LocalDateTime nextRetryAt;

    private String lastError;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
