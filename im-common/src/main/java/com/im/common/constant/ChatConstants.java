package com.im.common.constant;

import java.util.Set;

public final class ChatConstants {
    private ChatConstants() {}

    public static final String AI_ASSISTANT_ID = "0";
    public static final String AI_ASSISTANT_NAME = "AI助手";
    public static final String SYSTEM_USER = "SYSTEM";
    /** 系统通知会话账号（负数，避免与真实用户和 AI=0 冲突） */
    public static final long SYSTEM_ACCOUNT_ID = -1L;
    public static final String SYSTEM_ACCOUNT_NAME = "系统通知";

    /** 连接属性键：该连接经 JWT 认证出的用户 id（用于强制消息 from 归属，防冒充） */
    public static final String ATTR_AUTHED_USER_ID = "authedUserId";

    public static final int CHAT_TYPE_PRIVATE = 0;
    public static final int CHAT_TYPE_GROUP = 1;

    public static final String MSG_TYPE_TEXT = "TEXT";
    public static final String MSG_TYPE_IMAGE = "IMAGE";
    public static final String MSG_TYPE_VIDEO = "VIDEO";
    public static final String MSG_TYPE_FILE = "FILE";
    public static final String MSG_TYPE_VOICE = "VOICE";
    /** 通话记录消息（持久化，区别于瞬时 CALL_* 信令） */
    public static final String MSG_TYPE_CALL = "CALL";
    /** 系统事件消息（服务端下发，禁止客户端上行伪造） */
    public static final String MSG_TYPE_SYSTEM = "SYSTEM";
    /** 回执类型（服务端从不以此类型下发，仅为拒绝客户端伪造而防御性保留） */
    public static final String MSG_TYPE_ACK = "ACK";
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
    /** 瞬时消息标记：跨节点投递时目标离线则丢弃，不写离线队列 */
    public static final String HEADER_TRANSIENT = "transient";
    /** 写完后关闭连接（顶号/封禁控制事件） */
    public static final String HEADER_CLOSE_AFTER_WRITE = "closeAfterWrite";
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

    // ---------- 音视频通话信令（瞬时消息：不落库、不进离线、不计限流） ----------

    public static final String CALL_INVITE = "CALL_INVITE";
    public static final String CALL_RINGING = "CALL_RINGING";
    public static final String CALL_ACCEPT = "CALL_ACCEPT";
    public static final String CALL_REJECT = "CALL_REJECT";
    public static final String CALL_CANCEL = "CALL_CANCEL";
    public static final String CALL_BUSY = "CALL_BUSY";
    public static final String CALL_OFFER = "CALL_OFFER";
    public static final String CALL_ANSWER = "CALL_ANSWER";
    public static final String CALL_ICE = "CALL_ICE";
    public static final String CALL_HANGUP = "CALL_HANGUP";
    public static final String CALL_TIMEOUT = "CALL_TIMEOUT";

    public static final String HEADER_CALL_ID = "callId";
    /** 通话发起人 userId（用于前端区分呼入/呼出与文案） */
    public static final String HEADER_CALL_CALLER = "caller";
    /** 媒体类型：audio / video */
    public static final String HEADER_CALL_MEDIA = "media";
    /** 通话记录状态：COMPLETED/REJECTED/CANCELED/MISSED/FAILED */
    public static final String HEADER_CALL_STATUS = "callStatus";
    /** 拒绝/失败原因 */
    public static final String HEADER_CALL_REASON = "reason";
    /** 通话结束原因：对端离线/断线（服务端在用户无在线设备时自动结束通话并通知对端） */
    public static final String REASON_PEER_OFFLINE = "peer_offline";

    /** 合法的通话信令类型白名单（避免以前缀放行任意自造类型绕过限流等） */
    private static final Set<String> CALL_TYPES = Set.of(
            CALL_INVITE, CALL_RINGING, CALL_ACCEPT, CALL_REJECT, CALL_CANCEL,
            CALL_BUSY, CALL_OFFER, CALL_ANSWER, CALL_ICE, CALL_HANGUP, CALL_TIMEOUT);

    /** 是否为通话信令类型（瞬时，需从持久化/离线/限流中排除） */
    public static boolean isCallSignal(String type) {
        return type != null && CALL_TYPES.contains(type);
    }
}
