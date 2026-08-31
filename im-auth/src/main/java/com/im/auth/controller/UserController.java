package com.im.auth.controller;

import com.im.common.model.Result;
import com.im.core.friend.entity.FriendRequest;
import com.im.core.friend.service.FriendRequestService;
import com.im.core.friend.service.FriendService;
import com.im.core.user.entity.User;
import com.im.core.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final FriendService friendService;
    private final FriendRequestService friendRequestService;

    @GetMapping("/search")
    public Result<List<Map<String, Object>>> search(
            @RequestParam String keyword,
            @RequestAttribute("userId") String userId) {
        Long me = Long.parseLong(userId);
        List<User> users = userService.searchUsers(keyword, me);
        List<Map<String, Object>> result = users.stream()
                .map(u -> Map.<String, Object>of(
                    "userId", u.getId(),
                    "username", u.getUsername(),
                    "nickname", u.getNickname(),
                    "avatar", u.getAvatar() != null ? u.getAvatar() : "",
                    "signature", u.getSignature() != null ? u.getSignature() : "",
                    "isFriend", friendService.isFriend(me, u.getId()),
                    "requestStatus", getRequestStatus(me, u.getId())
                ))
                .toList();
        return Result.ok(result);
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> getUserById(@PathVariable Long id,
                                                   @RequestAttribute("userId") String userId) {
        Long me = Long.parseLong(userId);
        User user = userService.findById(id);
        if (user == null) {
            return Result.error("User not found");
        }
        return Result.ok(Map.<String, Object>of(
            "userId", user.getId(),
            "username", user.getUsername(),
            "nickname", user.getNickname(),
            "avatar", user.getAvatar() != null ? user.getAvatar() : "",
            "signature", user.getSignature() != null ? user.getSignature() : "",
            "isFriend", friendService.isFriend(me, id),
            "requestStatus", getRequestStatus(me, id)
        ));
    }

    private int getRequestStatus(Long me, Long otherId) {
        FriendRequest sent = friendRequestService.findByFromTo(me, otherId);
        if (sent != null && sent.getStatus() == 0) return 1;
        FriendRequest received = friendRequestService.findByFromTo(otherId, me);
        if (received != null && received.getStatus() == 0) return 2;
        return 0;
    }
}
