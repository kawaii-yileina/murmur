package com.im.core.cluster;

import com.elaina.chat.core.connection.Connection;
import com.im.core.cluster.route.RouteRegistry;

/**
 * 从连接属性中解析集群所需的设备信息。
 * 客户端在连接参数（如 ?deviceId=xxx&deviceType=desktop）中携带，由 {@link DeviceAttributeInitializer} 写入连接属性；
 * 未上报时回退为缺省设备（deviceId=default、deviceType=default）。
 * 注意：设备标识由客户端保证在同一账号下稳定且唯一（如持久化的安装 id）。
 */
public final class ConnAttributes {

    public static final String ATTR_DEVICE_ID = "deviceId";

    public static final String ATTR_DEVICE_TYPE = "deviceType";

    public static final String ATTR_PLATFORM = "platform";

    private ConnAttributes() {
    }

    public static String deviceId(Connection connection) {
        String value = raw(connection, ATTR_DEVICE_ID);
        return value != null ? value : RouteRegistry.DEFAULT_DEVICE_ID;
    }

    public static String deviceType(Connection connection) {
        return raw(connection, ATTR_DEVICE_TYPE);
    }

    public static String platform(Connection connection) {
        String value = raw(connection, ATTR_PLATFORM);
        return value != null ? value : "unknown";
    }

    private static String raw(Connection connection, String key) {
        if (connection == null || connection.attributes() == null) {
            return null;
        }
        Object value = connection.attributes().get(key);
        return value != null && !value.toString().isBlank() ? value.toString() : null;
    }
}
