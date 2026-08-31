package com.im.core.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.common.exception.BizException;
import com.im.core.friend.entity.Friend;
import com.im.core.friend.mapper.FriendMapper;
import com.im.core.user.entity.User;
import com.im.core.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

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

    public User createUser(String username, String password, String nickname) {
        if (findByUsername(username) != null) {
            throw new BizException("Username already exists");
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(password);
        user.setNickname(nickname != null && !nickname.isBlank() ? nickname : username);
        user.setStatus(0);
        userMapper.insert(user);
        return user;
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
