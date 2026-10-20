package com.im.core.friend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.common.enums.SystemEvent;
import com.im.common.exception.BizException;
import com.im.core.friend.entity.Friend;
import com.im.core.friend.entity.FriendRequest;
import com.im.core.friend.mapper.FriendMapper;
import com.im.core.friend.mapper.FriendRequestMapper;
import com.im.core.message.service.SystemMessageService;
import com.im.core.user.entity.User;
import com.im.core.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FriendRequestService {

    private final FriendRequestMapper friendRequestMapper;
    private final FriendMapper friendMapper;
    private final UserService userService;
    private final SystemMessageService systemMessageService;
    private final FriendCacheEvictor friendCacheEvictor;

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

        sendSystemNotification(String.valueOf(toId), SystemEvent.FRIEND_REQUEST, String.valueOf(fromId));

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
        // 好友关系建立：提交后再失效双方好友 id 缓存，避免事务内失效被并发读回填脏数据
        evictFriendIdsAfterCommit(request.getFromId());
        evictFriendIdsAfterCommit(request.getToId());

        request.setStatus(1);
        friendRequestMapper.updateById(request);

        // 通知申请人：申请已通过
        sendSystemNotification(String.valueOf(request.getFromId()), SystemEvent.FRIEND_ACCEPT, String.valueOf(userId));
        // 通知接受方：好友关系已建立。接受方自身的好友列表不含新好友，
        // 需同样收到事件以触发前端刷新，否则会显示"未知用户"。
        sendSystemNotification(String.valueOf(request.getToId()), SystemEvent.FRIEND_ACCEPT, String.valueOf(request.getFromId()));
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

        sendSystemNotification(String.valueOf(request.getFromId()), SystemEvent.FRIEND_REJECT, String.valueOf(userId));
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

    /** 事务提交后失效该用户的好友 id 缓存（无事务则立即失效） */
    private void evictFriendIdsAfterCommit(Long userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        friendCacheEvictor.evictFriendIds(userId);
                    } catch (Exception e) {
                        log.error("Evict friend cache failed for user {}: {}", userId, e.getMessage());
                    }
                }
            });
        } else {
            friendCacheEvictor.evictFriendIds(userId);
        }
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

    private void sendSystemNotification(String targetUserId, SystemEvent event, String... args) {
        try {
            String eventContent = event.format(args);
            String text = switch (event) {
                case FRIEND_REQUEST -> "「" + displayName(args[0]) + "」请求添加你为好友";
                case FRIEND_ACCEPT -> "你和「" + displayName(args[0]) + "」已成为好友";
                case FRIEND_REJECT -> "「" + displayName(args[0]) + "」拒绝了你的好友申请";
                default -> eventContent;
            };
            systemMessageService.sendNotification(Long.parseLong(targetUserId), text, eventContent);
        } catch (Exception e) {
            log.error("Failed to send {} notification to {}: {}", event.getType(), targetUserId, e.getMessage());
        }
    }

    private String displayName(String userId) {
        try {
            User user = userService.findById(Long.parseLong(userId));
            if (user != null) {
                return user.getNickname() != null && !user.getNickname().isBlank()
                        ? user.getNickname() : user.getUsername();
            }
        } catch (Exception ignored) {
            // 回退到 id，避免通知失败
        }
        return userId;
    }
}
