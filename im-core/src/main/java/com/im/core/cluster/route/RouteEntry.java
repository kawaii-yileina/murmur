package com.im.core.cluster.route;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一条连接路由记录：某个用户的某个设备当前连接在哪个节点。
 *
 * 以 Redis Hash 存于 im:conn:{userId}:{deviceId}（field: nodeId/connId/epoch/deviceId/platform/deviceType/loginAt），
 * 便于 Lua 脚本原子校验所有权（nodeId + connId）与登录代次（epoch）。
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

    /** 登录代次：同一 user+device 全局单调递增，代次大者胜（用于顶号/防旧注册覆盖） */
    private long epoch;

    private String platform;

    private String deviceType;

    /** 登录时间戳（毫秒） */
    private long loginAt;
}
