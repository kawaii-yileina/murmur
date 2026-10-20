package com.im.auth.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.common.exception.BizException;
import com.im.common.model.Result;
import com.im.core.admin.entity.Admin;
import com.im.core.admin.service.AdminService;
import com.im.core.admin.service.UserModerationService;
import com.im.core.group.mapper.GroupMapper;
import com.im.core.message.mapper.MessageMapper;
import com.im.core.message.service.SystemMessageService;
import com.im.core.user.entity.User;
import com.im.core.user.service.UserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserService userService;
    private final UserModerationService userModerationService;
    private final AdminService adminService;
    private final SystemMessageService systemMessageService;
    private final PasswordEncoder passwordEncoder;
    private final GroupMapper groupMapper;
    private final MessageMapper messageMapper;
    private final StringRedisTemplate redisTemplate;

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        Set<String> nodeKeys = redisTemplate.keys("im:node:*");
        long nodes = nodeKeys == null ? 0 : nodeKeys.stream()
                .filter(key -> !key.endsWith(":conns"))
                .filter(redisTemplate::hasKey)
                .count();
        Set<String> onlineKeys = redisTemplate.keys("im:online:*");
        return Result.ok(Map.of(
                "users", userService.countAll(),
                "onlineUsers", onlineKeys == null ? 0 : onlineKeys.size(),
                "bannedUsers", userService.countBanned(),
                "todayUsers", userService.countCreatedToday(),
                "groups", groupMapper.selectCount(null),
                "messages", messageMapper.selectCount(null),
                "nodes", Math.max(1, nodes)));
    }

    @GetMapping("/users")
    public Result<Map<String, Object>> users(@RequestParam(defaultValue = "") String keyword,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(50, Math.max(1, size));
        List<Map<String, Object>> items = userService.listUsers(keyword, safePage, safeSize)
                .stream().map(this::userView).toList();
        return Result.ok(Map.of(
                "total", userService.countUsers(keyword),
                "items", items));
    }

    @PostMapping("/users/{userId}/ban")
    public Result<Void> ban(@RequestAttribute("adminId") Long adminId,
                            @PathVariable Long userId,
                            @RequestBody(required = false) BanRequest request) {
        userModerationService.ban(userId,
                request == null || request.getReason() == null ? "违反平台规则" : request.getReason(),
                adminId,
                request == null ? null : request.getDurationSeconds());
        return Result.ok();
    }

    @PostMapping("/users/{userId}/unban")
    public Result<Void> unban(@PathVariable Long userId) {
        userModerationService.unban(userId);
        return Result.ok();
    }

    @PostMapping("/messages/broadcast")
    public Result<Map<String, Object>> broadcast(@RequestBody BroadcastRequest request) {
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw new BizException(400, "消息内容不能为空");
        }
        List<Long> userIds = userService.activeUserIds();
        for (Long userId : userIds) {
            systemMessageService.sendToUser(userId, request.getContent().trim());
        }
        return Result.ok(Map.of("sent", userIds.size()));
    }

    @GetMapping("/admins")
    public Result<List<Map<String, Object>>> admins() {
        return Result.ok(adminService.list().stream().map(this::adminView).toList());
    }

    @PostMapping("/admins")
    public Result<Map<String, Object>> createAdmin(@RequestAttribute("adminId") Long adminId,
                                                   @RequestAttribute("adminRole") Integer adminRole,
                                                   @RequestBody CreateAdminRequest request) {
        requireSuper(adminRole);
        if (request.getUsername() == null || request.getUsername().isBlank()
                || request.getPassword() == null || request.getPassword().length() < 6) {
            throw new BizException(400, "账号或密码不合法");
        }
        Admin admin = adminService.create(request.getUsername().trim(),
                passwordEncoder.encode(request.getPassword()), 1, adminId);
        return Result.ok(adminView(admin));
    }

    @DeleteMapping("/admins/{adminId}")
    public Result<Void> deleteAdmin(@RequestAttribute("adminRole") Integer adminRole,
                                    @PathVariable Long adminId) {
        requireSuper(adminRole);
        adminService.delete(adminId);
        return Result.ok();
    }

    private void requireSuper(Integer role) {
        if (role == null || role != 0) {
            throw new BizException(403, "仅超级管理员可操作");
        }
    }

    private Map<String, Object> userView(User user) {
        Map<String, Object> view = new HashMap<>();
        view.put("id", user.getId());
        view.put("username", user.getUsername());
        view.put("nickname", user.getNickname());
        view.put("email", user.getEmail());
        view.put("avatar", user.getAvatar());
        view.put("status", user.getStatus());
        boolean banned = userService.isBanned(user);
        view.put("banned", banned);
        view.put("banReason", banned ? user.getBanReason() : null);
        view.put("bannedAt", banned ? user.getBannedAt() : null);
        view.put("banExpireAt", banned ? user.getBanExpireAt() : null);
        view.put("deleted", user.getDeleted() != null && user.getDeleted() == 1);
        view.put("createdAt", user.getCreatedAt());
        return view;
    }

    private Map<String, Object> adminView(Admin admin) {
        return Map.of(
                "id", admin.getId(),
                "username", admin.getUsername(),
                "role", admin.getRole(),
                "status", admin.getStatus(),
                "mustChangePassword", admin.getMustChangePassword() != null && admin.getMustChangePassword() == 1);
    }

    @Data
    public static class BanRequest {
        private String reason;
        /** 封禁时长（秒）；null/0 表示永久 */
        private Long durationSeconds;
    }

    @Data
    public static class BroadcastRequest {
        private String content;
    }

    @Data
    public static class CreateAdminRequest {
        private String username;
        private String password;
    }
}
