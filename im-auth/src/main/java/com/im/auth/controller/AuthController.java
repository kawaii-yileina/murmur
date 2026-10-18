package com.im.auth.controller;

import com.im.auth.jwt.JwtTokenProvider;
import com.im.auth.security.FileTokenCookie;
import com.im.common.enums.VerifyScene;
import com.im.common.exception.BizException;
import com.im.common.model.Result;
import com.im.core.auth.service.LoginAttemptService;
import com.im.core.auth.service.RefreshTokenService;
import com.im.core.auth.service.TokenVersionService;
import com.im.core.auth.service.VerificationCodeService;
import com.im.core.auth.service.WsTicketService;
import com.im.core.user.entity.User;
import com.im.core.user.service.UserService;
import com.im.core.online.service.OnlineStatusService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final OnlineStatusService onlineStatusService;
    private final RefreshTokenService refreshTokenService;
    private final VerificationCodeService verificationCodeService;
    private final TokenVersionService tokenVersionService;
    private final LoginAttemptService loginAttemptService;
    private final WsTicketService wsTicketService;
    private final FileTokenCookie fileTokenCookie;

    /**
     * 登出：撤销 refresh token（若提供）并置离线。
     * access token 为无状态 JWT，无法即时失效，依赖其短有效期（2h）自然过期。
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader,
                               @RequestBody(required = false) RefreshRequest request,
                               HttpServletResponse response) {
        fileTokenCookie.clear(response);
        if (request != null && request.getRefreshToken() != null) {
            refreshTokenService.revoke(request.getRefreshToken());
        }
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try {
                String token = authHeader.substring(7);
                String userId = jwtTokenProvider.getUserIdFromExpiredToken(token);
                if (userId != null) {
                    onlineStatusService.userOffline(userId);
                }
            } catch (Exception ignored) {
            }
        }
        return Result.ok();
    }

    @PostMapping("/register")
    public Result<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request,
                                                HttpServletResponse response) {
        // 注册强制邮箱验证码（V-06）：必须提供邮箱且验证码校验通过
        String email = normalizeEmail(request.getEmail());
        if (email == null) {
            throw new BizException(400, "注册必须使用邮箱验证码");
        }
        verificationCodeService.verify(VerifyScene.REGISTER, email, request.getCode());
        User user = userService.createUser(
            request.getUsername(),
            passwordEncoder.encode(request.getPassword()),
            request.getNickname(),
            email
        );
        return Result.ok(issueTokens(user.getId(), user.getUsername(), user.getNickname(), null, response));
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginRequest request,
                                             HttpServletResponse response) {
        loginAttemptService.checkLocked(request.getUsername());
        User user = userService.findByUsername(request.getUsername());
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            loginAttemptService.recordFailure(request.getUsername());
            throw new BizException("用户名或密码错误");
        }
        loginAttemptService.recordSuccess(request.getUsername());
        ensureActive(user);
        return Result.ok(issueTokens(user.getId(), user.getUsername(), user.getNickname(), user.getAvatar(), response));
    }

    /**
     * 修改密码：校验旧密码 → 更新 → 撤销全部 refresh token + 递增 token 版本，实现全端即时失效。
     */
    @PostMapping("/change-password")
    public Result<Void> changePassword(@RequestAttribute("userId") String userId,
                                       @Valid @RequestBody ChangePasswordRequest request) {
        User user = userService.findById(Long.parseLong(userId));
        if (user == null) {
            throw new BizException("User not found");
        }
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new BizException(400, "原密码错误");
        }
        userService.updatePassword(user.getId(), passwordEncoder.encode(request.getNewPassword()));
        refreshTokenService.revokeAllForUser(userId);
        tokenVersionService.bump(userId);
        return Result.ok();
    }

    /**
     * 注销账号（软删除）：校验密码 → 标记注销 → 撤销全部 refresh token + 递增 token 版本 → 全端登出。
     * 保留好友/会话/群成员等历史数据，仅将账号标记为已注销。
     */
    @PostMapping("/delete-account")
    public Result<Void> deleteAccount(@RequestAttribute("userId") String userId,
                                      @Valid @RequestBody DeleteAccountRequest request) {
        User user = userService.findById(Long.parseLong(userId));
        if (user == null) {
            throw new BizException("User not found");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BizException(400, "密码错误");
        }
        userService.softDelete(user.getId());
        refreshTokenService.revokeAllForUser(userId);
        tokenVersionService.bump(userId);
        onlineStatusService.userOffline(userId);
        return Result.ok();
    }

    /**
     * 发送邮箱验证码（注册 / 登录 / 找回密码）。
     * 场景相关校验 + 频控（冷却、小时/日上限、IP 上限）由 VerificationCodeService 保证。
     */
    @PostMapping("/send-code")
    public Result<Void> sendCode(@RequestBody SendCodeRequest request, HttpServletRequest httpRequest) {
        VerifyScene scene = VerifyScene.from(request.getScene());
        if (scene == null) {
            throw new BizException(400, "验证码场景不合法");
        }
        String email = normalizeEmail(request.getEmail());
        User existing = userService.findByEmail(email);
        if (scene == VerifyScene.REGISTER && existing != null) {
            throw new BizException(400, "该邮箱已被注册");
        }
        // V-07 邮箱枚举防护：登录/重置密码场景对未注册邮箱统一返回成功但不实际发送，
        // 与已注册邮箱的响应不可区分（频控依旧生效，防止被用于探测+刷接口）
        if ((scene == VerifyScene.LOGIN || scene == VerifyScene.RESET_PASSWORD) && existing == null) {
            return Result.ok();
        }
        verificationCodeService.send(scene, email, clientIp(httpRequest));
        return Result.ok();
    }

    /**
     * 邮箱验证码登录（兼容原有用户名密码登录）。
     */
    @PostMapping("/login/code")
    public Result<Map<String, Object>> loginByCode(@Valid @RequestBody LoginByCodeRequest request,
                                                   HttpServletResponse response) {
        String email = normalizeEmail(request.getEmail());
        verificationCodeService.verify(VerifyScene.LOGIN, email, request.getCode());
        User user = userService.findByEmail(email);
        if (user == null) {
            throw new BizException("该邮箱未注册");
        }
        ensureActive(user);
        return Result.ok(issueTokens(user.getId(), user.getUsername(), user.getNickname(), user.getAvatar(), response));
    }

    /**
     * 通过邮箱验证码重置密码：重置后撤销该用户全部 refresh token，所有端需重新登录。
     */
    @PostMapping("/reset-password")
    public Result<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        String email = normalizeEmail(request.getEmail());
        verificationCodeService.verify(VerifyScene.RESET_PASSWORD, email, request.getCode());
        User user = userService.findByEmail(email);
        if (user == null) {
            throw new BizException("该邮箱未注册");
        }
        ensureActive(user);
        userService.updatePassword(user.getId(), passwordEncoder.encode(request.getNewPassword()));
        refreshTokenService.revokeAllForUser(String.valueOf(user.getId()));
        tokenVersionService.bump(String.valueOf(user.getId()));
        return Result.ok();
    }

    /**
     * 刷新 access token：校验 refresh token → 轮换（旧 token 保留短暂宽限期）→ 返回新的双 token。
     */
    @PostMapping("/refresh")
    public Result<Map<String, Object>> refresh(@RequestBody RefreshRequest request,
                                               HttpServletResponse response) {
        String userId = refreshTokenService.validateAndGetUserId(request.getRefreshToken());
        if (userId == null) {
            throw new BizException(401, "登录状态已失效，请重新登录");
        }
        String newRefreshToken = refreshTokenService.rotate(request.getRefreshToken(), userId);

        User user = userService.findById(Long.parseLong(userId));
        Map<String, Object> data = new HashMap<>();
        data.put("token", jwtTokenProvider.generateToken(userId));
        data.put("refreshToken", newRefreshToken);
        fileTokenCookie.attach(response, (String) data.get("token"));
        if (user != null) {
            data.put("userId", user.getId());
            data.put("username", user.getUsername());
            data.put("nickname", user.getNickname());
            data.put("avatar", user.getAvatar() != null ? user.getAvatar() : "");
        }
        return Result.ok(data);
    }

    private Map<String, Object> issueTokens(Long userId, String username, String nickname, String avatar,
                                            HttpServletResponse response) {
        String id = String.valueOf(userId);
        Map<String, Object> data = new HashMap<>();
        String token = jwtTokenProvider.generateToken(id);
        data.put("token", token);
        data.put("refreshToken", refreshTokenService.issue(id));
        fileTokenCookie.attach(response, token);
        data.put("userId", userId);
        data.put("username", username);
        data.put("nickname", nickname);
        data.put("avatar", avatar != null ? avatar : "");
        return data;
    }

    @PostMapping("/ws-ticket")
    public Result<Map<String, Object>> wsTicket(@RequestAttribute("userId") String userId,
                                                @RequestHeader(value = "Authorization", required = false) String authHeader,
                                                HttpServletResponse response) {
        // 每次建立/重建 WS 连接时顺带续发文件 Cookie，避免旧会话或 Cookie 过期后图片请求 401
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            fileTokenCookie.attach(response, authHeader.substring(7));
        }
        WsTicketService.IssuedTicket ticket = wsTicketService.issue(userId);
        return Result.ok(Map.of("ticket", ticket.ticket(), "expiresIn", ticket.expiresIn()));
    }

    @GetMapping("/me")
    public Result<Map<String, Object>> getCurrentUser(@RequestAttribute("userId") String userId) {
        User user = userService.findById(Long.parseLong(userId));
        if (user == null) {
            throw new BizException("User not found");
        }
        return Result.ok(Map.of(
            "userId", user.getId(),
            "username", user.getUsername(),
            "email", user.getEmail() != null ? user.getEmail() : "",
            "emailVerified", user.getEmailVerified() != null && user.getEmailVerified() == 1,
            "nickname", user.getNickname(),
            "avatar", user.getAvatar() != null ? user.getAvatar() : "",
            "signature", user.getSignature() != null ? user.getSignature() : ""
        ));
    }

    @PutMapping("/me")
    public Result<Map<String, Object>> updateProfile(@RequestAttribute("userId") String userId,
                                                     @RequestBody UpdateProfileRequest request) {
        User user = userService.updateProfile(
            Long.parseLong(userId),
            request.getNickname(),
            request.getSignature(),
            request.getAvatar()
        );
        return Result.ok(Map.of(
            "userId", user.getId(),
            "username", user.getUsername(),
            "nickname", user.getNickname() != null ? user.getNickname() : "",
            "avatar", user.getAvatar() != null ? user.getAvatar() : "",
            "signature", user.getSignature() != null ? user.getSignature() : ""
        ));
    }

    @Data
    public static class UpdateProfileRequest {
        private String nickname;
        private String signature;
        private String avatar;
    }

    @Data
    public static class RegisterRequest {
        @NotBlank(message = "Username is required")
        private String username;
        @NotBlank(message = "Password is required")
        private String password;
        private String nickname;
        /** 必填：注册必须使用邮箱验证码 */
        private String email;
        /** 必填：邮箱验证码 */
        private String code;
    }

    @Data
    public static class LoginRequest {
        @NotBlank(message = "Username is required")
        private String username;
        @NotBlank(message = "Password is required")
        private String password;
    }

    @Data
    public static class SendCodeRequest {
        private String email;
        private String scene;
    }

    @Data
    public static class LoginByCodeRequest {
        @NotBlank(message = "Email is required")
        private String email;
        @NotBlank(message = "Code is required")
        private String code;
    }

    @Data
    public static class ResetPasswordRequest {
        @NotBlank(message = "Email is required")
        private String email;
        @NotBlank(message = "Code is required")
        private String code;
        @NotBlank(message = "New password is required")
        private String newPassword;
    }

    @Data
    public static class RefreshRequest {
        private String refreshToken;
    }

    @Data
    public static class ChangePasswordRequest {
        @NotBlank(message = "Old password is required")
        private String oldPassword;
        @NotBlank(message = "New password is required")
        private String newPassword;
    }

    @Data
    public static class DeleteAccountRequest {
        @NotBlank(message = "Password is required")
        private String password;
    }

    /** 已注销账号禁止登录/找回密码 */
    private void ensureActive(User user) {
        if (user.getDeleted() != null && user.getDeleted() == 1) {
            throw new BizException(403, "账号已注销");
        }
    }

    /** 归一化邮箱：去空格、转小写；空值返回 null */
    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase();
    }

    /** 取客户端 IP（优先代理头） */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
