package com.im.auth.controller;

import com.im.auth.jwt.JwtTokenProvider;
import com.im.common.exception.BizException;
import com.im.common.model.Result;
import com.im.core.user.entity.User;
import com.im.core.user.service.UserService;
import com.im.core.online.service.OnlineStatusService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final OnlineStatusService onlineStatusService;

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
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
    public Result<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request) {
        User user = userService.createUser(
            request.getUsername(),
            passwordEncoder.encode(request.getPassword()),
            request.getNickname()
        );
        String token = jwtTokenProvider.generateToken(String.valueOf(user.getId()));
        return Result.ok(Map.of(
            "token", token,
            "userId", user.getId(),
            "nickname", user.getNickname()
        ));
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        User user = userService.findByUsername(request.getUsername());
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BizException("Invalid username or password");
        }
        String token = jwtTokenProvider.generateToken(String.valueOf(user.getId()));
        return Result.ok(Map.of(
            "token", token,
            "userId", user.getId(),
            "nickname", user.getNickname(),
            "avatar", user.getAvatar() != null ? user.getAvatar() : ""
        ));
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
    }

    @Data
    public static class LoginRequest {
        @NotBlank(message = "Username is required")
        private String username;
        @NotBlank(message = "Password is required")
        private String password;
    }
}
