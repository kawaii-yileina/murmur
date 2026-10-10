package com.im.core.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权威会话序号：每个逻辑会话一行，last_seq 为该会话已分配的最大序号。
 *
 * 序号在同一数据库事务内通过 INSERT ... ON DUPLICATE KEY UPDATE last_seq = last_seq + 1 原子分配，
 * 行锁使同一会话的并发事务串行化，从而保证 seq 分配顺序 = 事务提交顺序。
 */
@Data
@TableName("t_conversation_seq")
public class ConversationSeq {

    @TableId(value = "conversation_id", type = IdType.INPUT)
    private String conversationId;

    private Long lastSeq;

    private LocalDateTime updatedAt;
}
