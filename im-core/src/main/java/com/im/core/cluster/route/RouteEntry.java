package com.im.core.cluster.route;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一条连接路由记录：某个用户的某个设备当前连接在哪个节点。
 *
 * 以 JSON 存于 Redis（im:conn:{userId}:{deviceId}），供跨节点投递查询。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RouteEntry {

    private String userId;

    private String deviceId;

    /** 所在节点标识 */
    private String nodeId;

    /** 连接级 ID（同节点内定位具体连接） */
    private String connId;

    private String platform;

    private String deviceType;

    /** 登录时间戳（毫秒） */
    private long loginAt;
}
