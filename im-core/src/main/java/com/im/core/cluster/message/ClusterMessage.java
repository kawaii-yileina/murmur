package com.im.core.cluster.message;

import com.elaina.chat.core.message.ChatMessage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 跨节点投递载荷：发送方节点把"目标节点 + 该节点上的目标设备 + 原始消息"打包投递。
 *
 * 消费者只负责回投，不再重走 Dispatcher（避免回环与重复落库）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClusterMessage {

    /** 消息唯一标识，用于消费端幂等去重 */
    private String msgId;

    /** 会话标识（私聊按双方 id 排序，群聊为群 id），用于队列选择保证同会话有序 */
    private String conversationId;

    /** 0=私聊 1=群聊 */
    private int chatType;

    /** 目标节点标识 */
    private String targetNode;

    /** 目标设备列表（同一节点内） */
    private List<ClusterTarget> targets;

    /** 原始消息 */
    private ChatMessage message;
}
