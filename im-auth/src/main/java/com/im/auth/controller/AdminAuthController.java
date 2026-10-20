package com.im.auth.controller;

import com.im.auth.security.AdminTokenService;
import com.im.common.exception.BizException;
import com.im.common.model.Result;
import com.im.core.admin.entity.Admin;
import com.im.core.admin.service.AdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminService adminService;
    private final PasswordEncoder passwordEncoder;
    private final AdminTokenService adminTokenService;

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        Admin admin = adminService.findByUsername(request.getUsername());
        if (admin == null || admin.getStatus() == null || admin.getStatus() != 1
                || !passwordEncoder.matches(request.getPassword(), admin.getPassword())) {
            throw new BizException(401, "管理员账号或密码错误");
        }
        return Result.ok(Map.of(
                "token", adminTokenService.generate(admin.getId(), admin.getRole()),
                "admin", adminView(admin)));
    }

    @GetMapping("/me")
    public Result<Map<String, Object>> me(@RequestAttribute("adminId") Long adminId) {
        Admin admin = adminService.findById(adminId);
        if (admin == null) {
            throw new BizException(401, "管理员不存在");
        }
        return Result.ok(adminView(admin));
    }

    @PostMapping("/change-password")
    public Result<Void> changePassword(@RequestAttribute("adminId") Long adminId,
                                       @Valid @RequestBody ChangePasswordRequest request) {
        Admin admin = adminService.findById(adminId);
        if (admin == null || !passwordEncoder.matches(request.getOldPassword(), admin.getPassword())) {
            throw new BizException(400, "原密码错误");
        }
        adminService.updatePassword(adminId, passwordEncoder.encode(request.getNewPassword()));
        return Result.ok();
    }

    private Map<String, Object> adminView(Admin admin) {
        return Map.of(
                "id", admin.getId(),
                "username", admin.getUsername(),
                "role", admin.getRole(),
                "mustChangePassword", admin.getMustChangePassword() != null && admin.getMustChangePassword() == 1);
    }

    @Data
    public static class LoginRequest {
        @NotBlank
        private String username;
        @NotBlank
        private String password;
    }

    @Data
    public static class ChangePasswordRequest {
        @NotBlank
        private String oldPassword;
        @NotBlank
        private String newPassword;
    }
}
