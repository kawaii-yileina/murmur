package com.im.auth.controller;

import com.im.common.model.Result;
import com.im.core.friend.service.FriendService;
import com.im.core.friend.entity.FriendRequest;
import com.im.core.friend.service.FriendRequestService;
import com.im.core.group.entity.Group;
import com.im.core.group.entity.GroupMember;
import com.im.core.group.service.GroupService;
import com.im.core.user.entity.User;
import com.im.core.user.service.UserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/contacts")
@RequiredArgsConstructor
public class ContactController {

    private final FriendService friendService;
    private final FriendRequestService friendRequestService;
    private final GroupService groupService;
    private final UserService userService;

    @GetMapping("/friends")
    public Result<List<Map<String, Object>>> getFriends(@RequestAttribute("userId") String userId) {
        List<User> friends = friendService.getFriends(Long.parseLong(userId));
        List<Map<String, Object>> result = friends.stream()
                .map(f -> Map.<String, Object>of(
                    "userId", f.getId(),
                    "username", f.getUsername() != null ? f.getUsername() : "",
                    "nickname", f.getNickname() != null ? f.getNickname() : "",
                    "avatar", f.getAvatar() != null ? f.getAvatar() : "",
                    "signature", f.getSignature() != null ? f.getSignature() : "",
                    "deleted", f.getDeleted() != null && f.getDeleted() == 1
                ))
                .toList();
        return Result.ok(result);
    }

    @PostMapping("/friends")
    public Result<Void> addFriend(@RequestAttribute("userId") String userId, 
                                  @RequestBody AddFriendRequest request) {
        friendRequestService.sendRequest(Long.parseLong(userId), request.getFriendId());
        return Result.ok();
    }

    @PostMapping("/requests")
    public Result<FriendRequest> sendRequest(@RequestAttribute("userId") String userId,
                                             @RequestBody AddFriendRequest request) {
        FriendRequest friendRequest = friendRequestService.sendRequest(
            Long.parseLong(userId), request.getFriendId());
        return Result.ok(friendRequest);
    }

    @GetMapping("/requests/received")
    public Result<List<Map<String, Object>>> getReceivedRequests(
            @RequestAttribute("userId") String userId) {
        Long me = Long.parseLong(userId);
        List<FriendRequest> requests = friendRequestService.getReceivedRequests(me);
        List<Map<String, Object>> result = requests.stream()
                .map(r -> {
                    User from = userService.findById(r.getFromId());
                    return Map.<String, Object>of(
                        "id", r.getId(),
                        "fromId", r.getFromId(),
                        "nickname", from != null ? from.getNickname() : "Unknown",
                        "avatar", from != null && from.getAvatar() != null ? from.getAvatar() : "",
                        "createdAt", r.getCreatedAt()
                    );
                })
                .toList();
        return Result.ok(result);
    }

    @GetMapping("/requests/sent")
    public Result<List<Map<String, Object>>> getSentRequests(
            @RequestAttribute("userId") String userId) {
        Long me = Long.parseLong(userId);
        List<FriendRequest> requests = friendRequestService.getSentRequests(me);
        List<Map<String, Object>> result = requests.stream()
                .map(r -> {
                    User to = userService.findById(r.getToId());
                    return Map.<String, Object>of(
                        "id", r.getId(),
                        "toId", r.getToId(),
                        "nickname", to != null ? to.getNickname() : "Unknown",
                        "avatar", to != null && to.getAvatar() != null ? to.getAvatar() : "",
                        "createdAt", r.getCreatedAt()
                    );
                })
                .toList();
        return Result.ok(result);
    }

    @PostMapping("/requests/{id}/accept")
    public Result<Void> acceptRequest(@RequestAttribute("userId") String userId,
                                      @PathVariable Long id) {
        friendRequestService.acceptRequest(id, Long.parseLong(userId));
        return Result.ok();
    }

    @PostMapping("/requests/{id}/reject")
    public Result<Void> rejectRequest(@RequestAttribute("userId") String userId,
                                      @PathVariable Long id) {
        friendRequestService.rejectRequest(id, Long.parseLong(userId));
        return Result.ok();
    }

    @DeleteMapping("/friends/{friendId}")
    public Result<Void> removeFriend(@RequestAttribute("userId") String userId,
                                     @PathVariable Long friendId) {
        friendService.removeFriend(Long.parseLong(userId), friendId);
        return Result.ok();
    }

    @GetMapping("/groups")
    public Result<List<Group>> getGroups(@RequestAttribute("userId") String userId) {
        return Result.ok(groupService.getUserGroups(Long.parseLong(userId)));
    }

    @GetMapping("/groups/{groupId}")
    public Result<Group> getGroup(@RequestAttribute("userId") String userId,
                                  @PathVariable Long groupId) {
        Long me = Long.parseLong(userId);
        Group group = groupService.findById(groupId);
        if (group == null) {
            return Result.error("Group not found");
        }
        if (!groupService.isMember(groupId, me)) {
            return Result.error(403, "无权查看该群");
        }
        return Result.ok(group);
    }

    @PostMapping("/groups")
    public Result<Group> createGroup(@RequestAttribute("userId") String userId,
                                     @RequestBody CreateGroupRequest request) {
        Group group = groupService.createGroup(request.getName(), Long.parseLong(userId));
        return Result.ok(group);
    }

    @PutMapping("/groups/{groupId}")
    public Result<Void> updateGroup(@RequestAttribute("userId") String userId,
                                    @PathVariable Long groupId,
                                    @RequestBody UpdateGroupRequest request) {
        groupService.updateGroupName(groupId, request.getName(), Long.parseLong(userId));
        return Result.ok();
    }

    @GetMapping("/groups/{groupId}/members")
    public Result<List<Map<String, Object>>> getGroupMembers(@RequestAttribute("userId") String userId,
                                                             @PathVariable Long groupId) {
        if (!groupService.isMember(groupId, Long.parseLong(userId))) {
            return Result.error(403, "无权查看该群成员");
        }
        List<GroupMember> members = groupService.getMembers(groupId);
        List<Map<String, Object>> result = members.stream()
                .map(m -> {
                    User user = userService.findById(m.getUserId());
                    return Map.<String, Object>of(
                        "userId", m.getUserId(),
                        "username", user != null && user.getUsername() != null ? user.getUsername() : "",
                        "nickname", user != null && user.getNickname() != null ? user.getNickname() : "",
                        "avatar", user != null && user.getAvatar() != null ? user.getAvatar() : "",
                        "role", m.getRole(),
                        "joinedAt", m.getJoinedAt(),
                        "deleted", user != null && user.getDeleted() != null && user.getDeleted() == 1
                    );
                })
                .toList();
        return Result.ok(result);
    }

    @PostMapping("/groups/{groupId}/members")
    public Result<Void> addGroupMember(@RequestAttribute("userId") String userId,
                                       @PathVariable Long groupId,
                                       @RequestBody AddMemberRequest request) {
        Long me = Long.parseLong(userId);
        if (!groupService.isMember(groupId, me)) {
            return Result.error(403, "仅群成员可邀请入群");
        }
        if (request.getUserId() == null) {
            return Result.error("userId 不能为空");
        }
        groupService.addMember(groupId, request.getUserId(), 0);
        return Result.ok();
    }

    @PostMapping("/groups/{groupId}/admins")
    public Result<Void> setGroupAdmin(@RequestAttribute("userId") String userId,
                                      @PathVariable Long groupId,
                                      @RequestBody AdminRequest request) {
        if (request.getUserId() == null) {
            return Result.error("userId 不能为空");
        }
        groupService.setAdmin(groupId, request.getUserId(), Long.parseLong(userId));
        return Result.ok();
    }

    @DeleteMapping("/groups/{groupId}/admins/{memberId}")
    public Result<Void> unsetGroupAdmin(@RequestAttribute("userId") String userId,
                                        @PathVariable Long groupId,
                                        @PathVariable Long memberId) {
        groupService.unsetAdmin(groupId, memberId, Long.parseLong(userId));
        return Result.ok();
    }

    @DeleteMapping("/groups/{groupId}/members/{memberId}")
    public Result<Void> removeGroupMember(@RequestAttribute("userId") String userId,
                                          @PathVariable Long groupId,
                                          @PathVariable Long memberId) {
        groupService.removeMember(groupId, memberId, Long.parseLong(userId));
        return Result.ok();
    }

    @DeleteMapping("/groups/{groupId}/quit")
    public Result<Void> quitGroup(@RequestAttribute("userId") String userId,
                                  @PathVariable Long groupId) {
        groupService.quitGroup(groupId, Long.parseLong(userId));
        return Result.ok();
    }

    @PostMapping("/groups/{groupId}/transfer")
    public Result<Void> transferGroupOwner(@RequestAttribute("userId") String userId,
                                           @PathVariable Long groupId,
                                           @RequestBody TransferOwnerRequest request) {
        groupService.transferOwner(groupId, request.getNewOwnerId(), Long.parseLong(userId));
        return Result.ok();
    }

    @GetMapping("/groups/{groupId}/check")
    public Result<Boolean> checkGroupMember(@RequestAttribute("userId") String callerId,
                                            @PathVariable Long groupId,
                                            @RequestParam Long userId) {
        Long me = Long.parseLong(callerId);
        if (!me.equals(userId)) {
            return Result.error(403, "只能查询自己的成员状态");
        }
        return Result.ok(groupService.isMember(groupId, me));
    }

    @Data
    public static class AddFriendRequest {
        private Long friendId;
    }

    @Data
    public static class CreateGroupRequest {
        private String name;
    }

    @Data
    public static class UpdateGroupRequest {
        private String name;
    }

    @Data
    public static class AddMemberRequest {
        private Long userId;
    }

    @Data
    public static class AdminRequest {
        private Long userId;
    }

    @Data
    public static class TransferOwnerRequest {
        private Long newOwnerId;
    }
}
