package com.im.core.message.dto;

import com.im.core.message.entity.Message;
import lombok.Data;

import java.util.List;

/**
 * 会话增量同步结果。
 * 客户端以 lastSeq 作为本地游标，下次携 sinceSeq=lastSeq 继续补拉，并配合 msgId 去重。
 */
@Data
public class MessageSyncResult {

    /** 本批消息（按权威 seq 升序） */
    private List<Message> messages;

    /** 本批最大 seq；无新增时等于请求的 sinceSeq。作为客户端下次补拉游标 */
    private long lastSeq;

    /** 是否还有更多（供客户端继续拉取） */
    private boolean hasMore;

    public MessageSyncResult(List<Message> messages, long lastSeq, boolean hasMore) {
        this.messages = messages;
        this.lastSeq = lastSeq;
        this.hasMore = hasMore;
    }
}
