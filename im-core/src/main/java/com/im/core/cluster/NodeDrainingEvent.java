package com.im.core.cluster;

/**
 * 节点开始排空（优雅下线）事件。由 {@link NodeHeartbeatService} 在收到关闭信号时最先发布，
 * 供链路外组件（如 OutboxDispatcher 停止领取新任务、im-app 就绪态切换）响应，不含推送依赖、不参与框架构造链。
 */
public record NodeDrainingEvent(String nodeId) {
}
