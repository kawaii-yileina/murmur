package com.im.hermes.security;

import com.im.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/**
 * AI 出站 SSRF 防护（渗透报告 R2-01/R4-04）：baseUrl 为用户可配置项，
 * 服务端会以其为出站目标发起 HTTP 请求，必须校验：
 * - 仅允许 http/https 协议；
 * - 目标主机解析出的全部 IP 不得为环回/私有网段（RFC1918）/链路本地/通配/多播/IPv6 ULA；
 * - 服务端自身固定配置的 Hermes 地址（如 http://localhost:8642）白名单放行。
 * 在每次构建出站 WebClient 时调用（含原生客户端与 OpenAI 兼容客户端），
 * 拦截时抛业务异常并记录告警日志（不输出 apiKey）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SsrfGuard {

    private final com.im.hermes.client.HermesProperties properties;

    /**
     * 校验出站 baseUrl；不合规抛 BizException。
     *
     * @param baseUrl 用户配置或服务端默认的出站基础地址（可带 /v1 后缀）
     */
    public void validateOutbound(String baseUrl) {
        String target = baseUrl == null || baseUrl.isBlank()
                ? properties.getBaseUrl()
                : baseUrl;
        // 服务端自身固定配置的 Hermes 地址视为可信白名单（本地 agent 服务）
        if (isServerDefault(target)) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(target.trim());
        } catch (IllegalArgumentException e) {
            throw new BizException(400, "AI 服务地址不可用");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new BizException(400, "AI 服务地址不可用");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BizException(400, "AI 服务地址不可用");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            log.warn("SSRF guard: failed to resolve AI baseUrl host {}", host);
            throw new BizException(400, "AI 服务地址不可用");
        }
        for (InetAddress addr : addresses) {
            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()
                    || addr.isAnyLocalAddress() || addr.isMulticastAddress()
                    || isUniqueLocalIpv6(addr)) {
                log.warn("SSRF guard: blocked AI baseUrl pointing to restricted address, host={}", host);
                throw new BizException(400, "AI 服务地址不可用");
            }
        }
    }

    /** 与服务端默认配置归一化后比对（忽略大小写、末尾斜杠与 /v1 后缀差异） */
    private boolean isServerDefault(String target) {
        return normalize(target).equals(normalize(properties.getBaseUrl()));
    }

    private String normalize(String url) {
        String s = url.trim().toLowerCase(Locale.ROOT);
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith("/v1")) {
            s = s.substring(0, s.length() - 3);
        }
        return s;
    }

    /** IPv6 唯一本地地址（fc00::/7）无现成判断，按前 8 位是否为 0xfc/0xfd 判定 */
    private boolean isUniqueLocalIpv6(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }
}
