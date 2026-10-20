package com.im.core.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.im.common.exception.BizException;
import com.im.core.cache.CacheNames;
import com.im.core.friend.entity.Friend;
import com.im.core.friend.mapper.FriendMapper;
import com.im.core.user.entity.User;
import com.im.core.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final FriendMapper friendMapper;

    public User findById(Long id) {
        return userMapper.selectById(id);
    }

    /** 按id查未注销用户：软删除账号对外不可见（V-10），内部展示场景（如群成员昵称回退）仍用 findById */
    public User findActiveById(Long id) {
        User user = userMapper.selectById(id);
        return user != null && !(user.getDeleted() != null && user.getDeleted() == 1) ? user : null;
    }

    public User findByUsername(String username) {
        return userMapper.selectOne(
            new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );
    }

    public User findByEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return userMapper.selectOne(
            new LambdaQueryWrapper<User>().eq(User::getEmail, email)
        );
    }

    public User createUser(String username, String password, String nickname) {
        return createUser(username, password, nickname, null);
    }

    /**
     * 创建用户；email 非空时视为已通过验证码校验的邮箱注册，直接标记已验证。
     */
    public User createUser(String username, String password, String nickname, String email) {
        if (findByUsername(username) != null) {
            throw new BizException("Username already exists");
        }
        if (email != null && findByEmail(email) != null) {
            throw new BizException("该邮箱已被注册");
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(password);
        user.setNickname(nickname != null && !nickname.isBlank() ? nickname : username);
        user.setStatus(0);
        if (email != null && !email.isBlank()) {
            user.setEmail(email);
            user.setEmailVerified(1);
        }
        userMapper.insert(user);
        return user;
    }

    /** 重置密码（入参为已加密的密码） */
    public void updatePassword(Long userId, String encodedPassword) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("User not found");
        }
        user.setPassword(encodedPassword);
        userMapper.updateById(user);
    }

    /** 注销账号（软删除）：标记 deleted=1 与注销时间，保留历史数据 */
    public void softDelete(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("User not found");
        }
        user.setDeleted(1);
        user.setDeletedAt(LocalDateTime.now());
        userMapper.updateById(user);
    }

    public boolean isDeleted(Long userId) {
        User user = userMapper.selectById(userId);
        return user == null || (user.getDeleted() != null && user.getDeleted() == 1);
    }

    /** 从给定用户 id 中筛出未注销的（群消息路由时跳过已注销用户） */
    public Set<Long> filterActiveUserIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        return userMapper.selectList(
                new LambdaQueryWrapper<User>()
                    .in(User::getId, ids)
                    .ne(User::getDeleted, 1)
        ).stream().map(User::getId).collect(Collectors.toSet());
    }

    public void updateStatus(Long id, int status) {
        User user = new User();
        user.setId(id);
        user.setStatus(status);
        userMapper.updateById(user);
    }

    /**
     * 用户搜索：转义 LIKE 通配符防止 %/_ 注入全量枚举（V-04）；
     * 过滤已注销账号；最小关键词长度 1，上限 30 条。
     */
    public List<User> searchUsers(String keyword, Long excludeUserId) {
        String trimmed = keyword == null ? "" : keyword.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        String escaped = trimmed
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return userMapper.selectList(
            new LambdaQueryWrapper<User>()
                .and(w -> w
                    .apply("username LIKE CONCAT('%', {0}, '%') ESCAPE '\\\\'", escaped)
                    .or()
                    .apply("nickname LIKE CONCAT('%', {0}, '%') ESCAPE '\\\\'", escaped))
                .ne(User::getId, excludeUserId)
                .ne(User::getDeleted, 1)
                .last("LIMIT 30")
        );
    }

    public User updateProfile(Long userId, String nickname, String signature, String avatar) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("User not found");
        }
        if (nickname != null) {
            user.setNickname(nickname.isBlank() ? user.getUsername() : nickname);
        }
        if (signature != null) {
            user.setSignature(signature);
        }
        if (avatar != null) {
            user.setAvatar(avatar);
        }
        userMapper.updateById(user);
        return user;
    }

    @Cacheable(cacheNames = CacheNames.FRIEND_IDS, key = "#userId")
    public List<Long> getFriendIds(Long userId) {
        return friendMapper.selectList(
            new LambdaQueryWrapper<Friend>().eq(Friend::getUserId, userId)
        ).stream().map(Friend::getFriendId).collect(Collectors.toList());
    }

    public List<User> listUsers(String keyword, int page, int size) {
        String trimmed = keyword == null ? "" : keyword.trim();
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .orderByDesc(User::getCreatedAt)
                .last("LIMIT " + size + " OFFSET " + Math.max(0, page - 1) * size);
        if (!trimmed.isEmpty()) {
            String escaped = trimmed.replace("\\", "\\\\")
                    .replace("%", "\\%").replace("_", "\\_");
            wrapper.and(w -> w
                    .apply("username LIKE CONCAT('%', {0}, '%') ESCAPE '\\\\'", escaped)
                    .or()
                    .apply("nickname LIKE CONCAT('%', {0}, '%') ESCAPE '\\\\'", escaped));
        }
        return userMapper.selectList(wrapper);
    }

    public long countUsers(String keyword) {
        String trimmed = keyword == null ? "" : keyword.trim();
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        if (!trimmed.isEmpty()) {
            String escaped = trimmed.replace("\\", "\\\\")
                    .replace("%", "\\%").replace("_", "\\_");
            wrapper.and(w -> w
                    .apply("username LIKE CONCAT('%', {0}, '%') ESCAPE '\\\\'", escaped)
                    .or()
                    .apply("nickname LIKE CONCAT('%', {0}, '%') ESCAPE '\\\\'", escaped));
        }
        return userMapper.selectCount(wrapper);
    }

    public long countAll() {
        return userMapper.selectCount(null);
    }

    public long countBanned() {
        return userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getBanned, 1)
                .and(w -> w.isNull(User::getBanExpireAt)
                        .or()
                        .gt(User::getBanExpireAt, LocalDateTime.now())));
    }

    public long countCreatedToday() {
        return userMapper.selectCount(new LambdaQueryWrapper<User>()
                .ge(User::getCreatedAt, LocalDateTime.now().toLocalDate().atStartOfDay()));
    }

    public List<Long> activeUserIds() {
        return userMapper.selectList(new LambdaQueryWrapper<User>()
                        .ne(User::getDeleted, 1)
                        .select(User::getId))
                .stream().map(User::getId).toList();
    }

    public void setBanned(Long userId, boolean banned, String reason, Long adminId) {
        setBanned(userId, banned, reason, adminId, null);
    }

    public void setBanned(Long userId, boolean banned, String reason, Long adminId, LocalDateTime banExpireAt) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getBanned, banned ? 1 : 0)
                .set(User::getBanReason, banned ? reason : null)
                .set(User::getBannedAt, banned ? LocalDateTime.now() : null)
                .set(User::getBannedBy, banned ? adminId : null)
                .set(User::getBanExpireAt, banned ? banExpireAt : null));
    }

    /** 是否处于有效封禁期；已到期的封禁按未封禁处理 */
    public boolean isBanned(User user) {
        if (user == null || user.getBanned() == null || user.getBanned() != 1) {
            return false;
        }
        return user.getBanExpireAt() == null || user.getBanExpireAt().isAfter(LocalDateTime.now());
    }
}
