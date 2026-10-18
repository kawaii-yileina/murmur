package com.im.core.search.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MessageRow {
    private String msgId;
    private Long seq;
    private Long targetId;
    private Integer chatType;
    private Long senderId;
    private String senderName;
    private String groupName;
    private String content;
    private LocalDateTime createdAt;
}
