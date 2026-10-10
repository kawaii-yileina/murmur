package com.im.core.cluster;

import com.elaina.chat.core.connection.ConnectionAttributeInitializer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 连接设备属性初始化器：在 WebSocket 握手时把客户端的设备标识写入连接属性。
 *
 * 提取来源（按优先级）：查询参数 > HTTP 头（x-device-id / x-device-type / x-platform）。
 *
 * 重要：框架的默认连接属性初始化器标注了 {@code @ConditionalOnMissingBean(ConnectionAttributeInitializer.class)}
 * （见 chat-nexus F-14），应用一旦新增任意同类型 Bean，框架默认实现即被顶掉，token/userId 将不再提取。
 * 因此本类必须同时复制框架默认行为（提取 userId / token），否则鉴权会失效。
 */
@Component
public class DeviceAttributeInitializer implements ConnectionAttributeInitializer {

    /** deviceId 白名单：仅允许字母数字与 . _ -，长度 1~64；防止非法字符（如 `:`）破坏路由键 */
    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    public void initialize(Map<String, List<String>> parameters,
                           Map<String, String> headers,
                           Map<String, Object> targetAttributes) {
        // 复制框架默认行为（userId / token），避免默认初始化器被顶掉导致鉴权失效
        putParamFirst(parameters, targetAttributes, "userId");
        putParamFirst(parameters, targetAttributes, "token");
        // 设备标识
        putDeviceId(parameters, headers, targetAttributes);
        put(parameters, headers, targetAttributes, ConnAttributes.ATTR_DEVICE_TYPE, "x-device-type");
        put(parameters, headers, targetAttributes, ConnAttributes.ATTR_PLATFORM, "x-platform");
    }

    private void putDeviceId(Map<String, List<String>> parameters, Map<String, String> headers,
                             Map<String, Object> targetAttributes) {
        String value = first(parameters, ConnAttributes.ATTR_DEVICE_ID);
        if (value == null && headers != null) {
            value = headers.get("x-device-id");
        }
        if (value != null) {
            value = value.trim();
            if (DEVICE_ID_PATTERN.matcher(value).matches()) {
                targetAttributes.put(ConnAttributes.ATTR_DEVICE_ID, value);
            }
        }
    }

    private void putParamFirst(Map<String, List<String>> parameters, Map<String, Object> targetAttributes, String key) {
        String value = first(parameters, key);
        if (value != null && !value.isBlank()) {
            targetAttributes.put(key, value);
        }
    }

    private void put(Map<String, List<String>> parameters, Map<String, String> headers,
                     Map<String, Object> targetAttributes, String key, String headerName) {
        String value = first(parameters, key);
        if (value == null && headers != null) {
            value = headers.get(headerName);
        }
        if (value != null && !value.isBlank()) {
            targetAttributes.put(key, value.trim());
        }
    }

    private String first(Map<String, List<String>> parameters, String key) {
        if (parameters == null) {
            return null;
        }
        List<String> values = parameters.get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}
