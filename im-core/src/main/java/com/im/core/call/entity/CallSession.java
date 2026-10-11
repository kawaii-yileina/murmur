package com.im.core.call.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通话记录（G2）。一次通话一行，配合聊天内 CALL 类型消息展示。
 */
@Data
@TableName("t_call_session")
public class CallSession {

    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CANCELED = "CANCELED";
    public static final String STATUS_MISSED = "MISSED";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String callId;

    private Long callerId;

    private Long calleeId;

    /** 0=语音 1=视频 */
    private Integer mediaType;

    private String status;

    private LocalDateTime startedAt;

    private LocalDateTime answeredAt;

    private LocalDateTime endedAt;

    private Integer durationSeconds;

    private LocalDateTime createdAt;
}
