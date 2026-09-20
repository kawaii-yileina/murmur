package com.im.core.friend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.core.friend.entity.Friend;
import com.im.core.friend.mapper.FriendMapper;
import com.im.core.user.entity.User;
import com.im.core.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FriendService {

    private final FriendMapper friendMapper;
    private final UserService userService;

    public List<User> getFriends(Long userId) {
        List<Friend> friendships = friendMapper.selectList(
            new LambdaQueryWrapper<Friend>().eq(Friend::getUserId, userId)
        );
        return friendships.stream()
                .map(f -> userService.findById(f.getFriendId()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public void removeFriend(Long userId, Long friendId) {
        friendMapper.delete(new LambdaQueryWrapper<Friend>()
                .eq(Friend::getUserId, userId).eq(Friend::getFriendId, friendId));
        friendMapper.delete(new LambdaQueryWrapper<Friend>()
                .eq(Friend::getUserId, friendId).eq(Friend::getFriendId, userId));
    }

    public boolean isFriend(Long userId, Long friendId) {
        return friendMapper.selectCount(new LambdaQueryWrapper<Friend>()
                .eq(Friend::getUserId, userId).eq(Friend::getFriendId, friendId)) > 0;
    }
}
