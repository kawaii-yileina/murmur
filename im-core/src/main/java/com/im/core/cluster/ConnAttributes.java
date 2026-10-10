package com.im.core.cluster;

import com.elaina.chat.core.connection.Connection;
import com.im.core.cluster.route.RouteRegistry;

/**
 * 从连接属性中解析集群所需的设备信息。
 *
 * 目前客户端尚未上报 deviceId/deviceType，缺省使用 {@link RouteRegistry#DEFAULT_DEVICE_ID}；
 * 后续接入多端时，由客户端在连接参数中携带 deviceId 并在连接属性里体现。
 */
public final class ConnAttributes {

    public static final String ATTR_DEVICE_ID = "deviceId";

    public static final String ATTR_PLATFORM = "platform";

    private ConnAttributes() {
    }

    public static String deviceId(Connection connection) {
        Object value = connection.attributes().get(ATTR_DEVICE_ID);
        return value != null && !value.toString().isBlank() ? value.toString() : RouteRegistry.DEFAULT_DEVICE_ID;
    }

    public static String platform(Connection connection) {
        Object value = connection.attributes().get(ATTR_PLATFORM);
        return value != null && !value.toString().isBlank() ? value.toString() : "unknown";
    }
}
