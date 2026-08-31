package com.im.core.friend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.common.exception.BizException;
import com.im.core.friend.entity.Friend;
import com.im.core.friend.entity.FriendRequest;
import com.im.core.friend.mapper.FriendMapper;
import com.im.core.friend.mapper.FriendRequestMapper;
import com.im.core.user.entity.User;
import com.im.core.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FriendRequestService {

    private final FriendRequestMapper friendRequestMapper;
    private final FriendMapper friendMapper;
    private final UserService userService;

    public FriendRequest sendRequest(Long fromId, Long toId) {
        if (fromId.equals(toId)) {
            throw new BizException("Cannot add yourself as friend");
        }
        if (isFriend(fromId, toId)) {
            throw new BizException("You are already friends");
        }

        FriendRequest existing = friendRequestMapper.selectOne(
            new LambdaQueryWrapper<FriendRequest>()
                .eq(FriendRequest::getFromId, fromId)
                .eq(FriendRequest::getToId, toId)
        );
        if (existing != null) {
            if (existing.getStatus() == 0) {
                throw new BizException("Request already sent");
            }
            if (existing.getStatus() == 1) {
                throw new BizException("You are already friends");
            }
            existing.setStatus(0);
            friendRequestMapper.updateById(existing);
            return existing;
        }

        FriendRequest request = new FriendRequest();
        request.setFromId(fromId);
        request.setToId(toId);
        request.setStatus(0);
        friendRequestMapper.insert(request);
        return request;
    }

    public List<FriendRequest> getReceivedRequests(Long userId) {
        return friendRequestMapper.selectList(
            new LambdaQueryWrapper<FriendRequest>()
                .eq(FriendRequest::getToId, userId)
                .eq(FriendRequest::getStatus, 0)
                .orderByDesc(FriendRequest::getCreatedAt)
        );
    }

    public List<FriendRequest> getSentRequests(Long userId) {
        return friendRequestMapper.selectList(
            new LambdaQueryWrapper<FriendRequest>()
                .eq(FriendRequest::getFromId, userId)
                .eq(FriendRequest::getStatus, 0)
                .orderByDesc(FriendRequest::getCreatedAt)
        );
    }

    @Transactional
    public void acceptRequest(Long id, Long userId) {
        FriendRequest request = friendRequestMapper.selectById(id);
        if (request == null) {
            throw new BizException("Request not found");
        }
        if (!request.getToId().equals(userId)) {
            throw new BizException("No permission to accept this request");
        }
        if (request.getStatus() != 0) {
            throw new BizException("Request already processed");
        }

        addFriendBidirectional(request.getFromId(), request.getToId());

        request.setStatus(1);
        friendRequestMapper.updateById(request);
    }

    @Transactional
    public void rejectRequest(Long id, Long userId) {
        FriendRequest request = friendRequestMapper.selectById(id);
        if (request == null) {
            throw new BizException("Request not found");
        }
        if (!request.getToId().equals(userId)) {
            throw new BizException("No permission to reject this request");
        }
        if (request.getStatus() != 0) {
            throw new BizException("Request already processed");
        }

        request.setStatus(2);
        friendRequestMapper.updateById(request);
    }

    public User getCounterparty(FriendRequest request, Long userId) {
        Long otherId = request.getFromId().equals(userId)
            ? request.getToId() : request.getFromId();
        return userService.findById(otherId);
    }

    public FriendRequest findByFromTo(Long fromId, Long toId) {
        return friendRequestMapper.selectOne(
            new LambdaQueryWrapper<FriendRequest>()
                .eq(FriendRequest::getFromId, fromId)
                .eq(FriendRequest::getToId, toId)
        );
    }

    public boolean isFriend(Long userId, Long friendId) {
        return friendMapper.selectCount(new LambdaQueryWrapper<Friend>()
                .eq(Friend::getUserId, userId).eq(Friend::getFriendId, friendId)) > 0;
    }

    private void addFriendBidirectional(Long a, Long b) {
        if (!isFriend(a, b)) {
            Friend f1 = new Friend();
            f1.setUserId(a);
            f1.setFriendId(b);
            friendMapper.insert(f1);
        }
        if (!isFriend(b, a)) {
            Friend f2 = new Friend();
            f2.setUserId(b);
            f2.setFriendId(a);
            friendMapper.insert(f2);
        }
    }
}
