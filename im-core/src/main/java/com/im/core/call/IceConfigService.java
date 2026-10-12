package com.im.core.call;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ICE 服务器配置服务（G4）：向客户端下发 STUN/TURN 配置。
 *
 * TURN 使用静态共享密钥 + 临时凭证（coturn use-auth-secret 约定）：
 * username = "过期时间戳:userId"，credential = base64(HMAC-SHA1(sharedSecret, username))。
 * 每次请求生成新凭证，避免长期密钥下发到客户端；未启用 TURN 或未配置密钥时只下发 STUN。
 * 纯计算，无外部依赖，供 REST 层调用。
 */
@Slf4j
@Service
public class IceConfigService {

    @Value("${im.call.stun-urls:stun:stun.l.google.com:19302}")
    private String stunUrls;

    @Value("${im.call.turn.enabled:false}")
    private boolean turnEnabled;

    @Value("${im.call.turn.urls:}")
    private String turnUrls;

    @Value("${im.call.turn.shared-secret:}")
    private String turnSharedSecret;

    @Value("${im.call.turn.ttl-seconds:3600}")
    private long turnTtlSeconds;

    /** 生成 ICE 配置：{"iceServers":[...],"ttl":秒}，结构可直接用于 RTCPeerConnection */
    public Map<String, Object> iceConfig(String userId) {
        List<Map<String, Object>> servers = new ArrayList<>();
        List<String> stun = splitUrls(stunUrls);
        if (!stun.isEmpty()) {
            servers.add(Map.of("urls", stun));
        }
        List<String> turn = splitUrls(turnUrls);
        if (turnEnabled && !turn.isEmpty() && !turnSharedSecret.isBlank()) {
            long expiry = System.currentTimeMillis() / 1000 + turnTtlSeconds;
            String username = expiry + ":" + userId;
            Map<String, Object> turnServer = new LinkedHashMap<>();
            turnServer.put("urls", turn);
            turnServer.put("username", username);
            turnServer.put("credential", hmacSha1Base64(turnSharedSecret, username));
            servers.add(turnServer);
        }
        return Map.of("iceServers", servers, "ttl", turnTtlSeconds);
    }

    private List<String> splitUrls(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String u = s.trim();
            if (!u.isEmpty()) {
                out.add(u);
            }
        }
        return out;
    }

    private String hmacSha1Base64(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.warn("Generate TURN credential failed: {}", e.getMessage());
            return "";
        }
    }
}
