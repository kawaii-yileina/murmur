package com.im.auth.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 文件访问凭证 Cookie：HttpOnly + 限定 /api/files/local 路径，避免把 JWT 放进 URL。
 * 生产跨站（Capacitor/独立前端域名）需配置 secure=true、same-site=None。
 */
@Component
public class FileTokenCookie {

    public static final String NAME = "im_file_token";

    @Value("${jwt.expiration:7200000}")
    private long ttlMillis;

    @Value("${im.file.cookie-secure:false}")
    private boolean secure;

    @Value("${im.file.cookie-same-site:Lax}")
    private String sameSite;

    public void attach(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(token, Duration.ofMillis(ttlMillis)).toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .path("/api/files/local")
                .sameSite(sameSite)
                .maxAge(maxAge)
                .build();
    }
}
