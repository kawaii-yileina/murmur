package com.im.common.constant;

public final class ChatConstants {
    private ChatConstants() {}

    public static final String AI_ASSISTANT_ID = "0";
    public static final String AI_ASSISTANT_NAME = "AI助手";
    public static final String SYSTEM_USER = "SYSTEM";

    public static final int CHAT_TYPE_PRIVATE = 0;
    public static final int CHAT_TYPE_GROUP = 1;

    public static final String MSG_TYPE_TEXT = "TEXT";
    public static final String MSG_TYPE_IMAGE = "IMAGE";
    public static final String MSG_TYPE_VIDEO = "VIDEO";
    public static final String MSG_TYPE_FILE = "FILE";
    public static final String MSG_TYPE_VOICE = "VOICE";
    /** 输入中状态（瞬时事件，不落库、不投离线） */
    public static final String MSG_TYPE_TYPING = "TYPING";

    public static final String HEADER_DURATION = "duration";
    public static final String HEADER_SIZE = "size";
    public static final String HEADER_WIDTH = "width";
    public static final String HEADER_HEIGHT = "height";
    public static final String HEADER_FORMAT = "format";
    public static final String HEADER_ORIGINAL_NAME = "originalName";
    /** 引用回复信息（JSON 字符串） */
    public static final String HEADER_REPLY_TO = "replyTo";
    /** @提及成员信息（JSON 数组字符串） */
    public static final String HEADER_MENTIONS = "mentions";
    /** 客户端消息 id（仅用于 ACK 关联，不落库） */
    public static final String HEADER_CLIENT_MSG_ID = "clientMsgId";
    /** 服务端 ACK 标记（仅回执使用，不落库） */
    public static final String HEADER_ACK = "ack";
    /** 服务端消息 id（仅回执使用，不落库） */
    public static final String HEADER_SERVER_MSG_ID = "serverMsgId";
    /** 已送达目标列表（仅回执使用，不落库） */
    public static final String HEADER_DELIVERED = "delivered";
    /**
     * 会话权威序号：持久化投递路径（Outbox 本地投递/跨节点消费/离线补投）在消息 headers 附带，
     * 供客户端按 seq 排序；仅下行附带，不落库（t_message.seq 为独立列）。
     */
    public static final String HEADER_SEQ = "seq";
    /** @所有人的特殊标识（成员 id 位） */
    public static final String MENTION_ALL = "all";

    /** 会话列表预览占位：撤回者是当前用户 */
    public static final String RECALL_PREVIEW_SELF = "__RECALL_SELF__";
    /** 会话列表预览占位：撤回者是对方 */
    public static final String RECALL_PREVIEW_OTHER = "__RECALL_OTHER__";
}
