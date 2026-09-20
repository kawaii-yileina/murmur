package com.im.core.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.common.exception.BizException;
import com.im.core.friend.entity.Friend;
import com.im.core.friend.mapper.FriendMapper;
import com.im.core.user.entity.User;
import com.im.core.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
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

    public List<User> searchUsers(String keyword, Long excludeUserId) {
        return userMapper.selectList(
            new LambdaQueryWrapper<User>()
                .and(w -> w.like(User::getUsername, keyword)
                    .or().like(User::getNickname, keyword))
                .ne(User::getId, excludeUserId)
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

    public List<Long> getFriendIds(Long userId) {
        return friendMapper.selectList(
            new LambdaQueryWrapper<Friend>().eq(Friend::getUserId, userId)
        ).stream().map(Friend::getFriendId).toList();
    }
}
