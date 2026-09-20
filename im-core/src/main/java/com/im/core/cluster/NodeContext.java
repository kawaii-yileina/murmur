package com.im.core.cluster;

import lombok.Getter;

/**
 * 当前节点上下文：节点标识与默认设备类型。
 *
 * 供连接路由表、跨节点投递与日志追踪使用；多实例部署时每个实例的 nodeId 必须唯一。
 */
@Getter
public class NodeContext {

    private final String nodeId;

    private final String defaultDeviceType;

    public NodeContext(String nodeId, String defaultDeviceType) {
        this.nodeId = nodeId;
        this.defaultDeviceType = defaultDeviceType;
    }
}
