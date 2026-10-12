package com.im.auth.controller;

import com.im.common.model.Result;
import com.im.core.call.IceConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 通话辅助接口：下发 ICE 服务器配置（STUN + TURN 临时凭证）。
 */
@RestController
@RequestMapping("/api/call")
@RequiredArgsConstructor
public class CallController {

    private final IceConfigService iceConfigService;

    @GetMapping("/ice-config")
    public Result<Map<String, Object>> iceConfig(@RequestAttribute("userId") String userId) {
        return Result.ok(iceConfigService.iceConfig(userId));
    }
}
