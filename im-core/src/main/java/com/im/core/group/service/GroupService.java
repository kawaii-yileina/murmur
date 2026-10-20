package com.im.core.group.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.common.enums.SystemEvent;
import com.im.common.exception.BizException;
import com.im.core.cache.CacheNames;
import com.im.core.group.entity.Group;
import com.im.core.group.entity.GroupMember;
import com.im.core.group.mapper.GroupMapper;
import com.im.core.group.mapper.GroupMemberMapper;
import com.im.core.message.service.SystemMessageService;
import com.im.core.user.entity.User;
import com.im.core.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupService {

    private final GroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final SystemMessageService systemMessageService;
    private final UserService userService;

    @Value("${im.group.max-admins:3}")
    private int maxAdmins;

    public Group createGroup(String name, Long ownerId) {
        Group group = new Group();
        group.setName(name);
        group.setOwnerId(ownerId);
        groupMapper.insert(group);

        addMember(group.getId(), ownerId, 2);
        return group;
    }

    @Cacheable(cacheNames = CacheNames.GROUP_INFO, key = "#groupId")
    public Group findById(Long groupId) {
        return groupMapper.selectById(groupId);
    }

    @CacheEvict(cacheNames = CacheNames.GROUP_MEMBERS, key = "#groupId")
    public void addMember(Long groupId, Long userId, int role) {
        if (isMember(groupId, userId)) {
            throw new BizException(400, "User is already a member of this group");
        }
        GroupMember member = new GroupMember();
        member.setGroupId(groupId);
        member.setUserId(userId);
        member.setRole(role);
        member.setJoinedAt(LocalDateTime.now());
        groupMemberMapper.insert(member);

        Group group = findById(groupId);
        String text = "「" + displayName(userId) + "」加入群聊「" + groupName(group, groupId) + "」";
        sendGroupNotification(groupId, SystemEvent.GROUP_ADD, text,
                String.valueOf(groupId), String.valueOf(userId));
    }

    @Transactional
    @CacheEvict(cacheNames = CacheNames.GROUP_MEMBERS, key = "#groupId")
    public void removeMember(Long groupId, Long userId, Long operatorId) {
        Group group = findById(groupId);
        if (group == null) {
            throw new BizException(404, "Group not found");
        }
        if (userId.equals(group.getOwnerId())) {
            throw new BizException(403, "Cannot remove group owner");
        }

        GroupMember target = findMember(groupId, userId);
        if (target == null) {
            throw new BizException(404, "Member not found");
        }

        if (!userId.equals(operatorId)) {
            if (isOwner(groupId, operatorId)) {
                // 群主可移除管理员和普通成员
            } else {
                GroupMember operator = findMember(groupId, operatorId);
                if (operator == null || operator.getRole() != 1 || target.getRole() != 0) {
                    throw new BizException(403, "No permission to remove member");
                }
            }
        }

        List<String> memberIds = getMemberIds(groupId);
        groupMemberMapper.delete(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId));

        sendGroupNotificationTo(memberIds, SystemEvent.GROUP_KICK,
                "「" + displayName(userId) + "」被移出群聊「" + groupName(group, groupId) + "」",
                String.valueOf(groupId), String.valueOf(userId), String.valueOf(operatorId));
    }

    @Transactional
    @CacheEvict(cacheNames = CacheNames.GROUP_MEMBERS, key = "#groupId")
    public void setAdmin(Long groupId, Long userId, Long operatorId) {
        Group group = groupMapper.selectForUpdate(groupId);
        if (group == null) {
            throw new BizException(404, "Group not found");
        }
        if (!operatorId.equals(group.getOwnerId())) {
            throw new BizException(403, "Only group owner can set admin");
        }
        if (userId.equals(group.getOwnerId())) {
            throw new BizException(400, "Group owner cannot be set as admin");
        }

        GroupMember target = findMember(groupId, userId);
        if (target == null) {
            throw new BizException(404, "Member not found");
        }
        if (target.getRole() == 1) {
            return;
        }
        long adminCount = groupMemberMapper.selectCount(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getRole, 1));
        if (adminCount >= maxAdmins) {
            throw new BizException(400, "Admin limit reached");
        }

        updateMemberRole(groupId, userId, 1);
        sendGroupNotification(groupId, SystemEvent.GROUP_ADMIN_SET,
                "「" + displayName(userId) + "」被设为群聊「" + groupName(group, groupId) + "」管理员",
                String.valueOf(groupId), String.valueOf(userId), String.valueOf(operatorId));
    }

    @Transactional
    @CacheEvict(cacheNames = CacheNames.GROUP_MEMBERS, key = "#groupId")
    public void unsetAdmin(Long groupId, Long userId, Long operatorId) {
        Group group = groupMapper.selectForUpdate(groupId);
        if (group == null) {
            throw new BizException(404, "Group not found");
        }
        if (!operatorId.equals(group.getOwnerId())) {
            throw new BizException(403, "Only group owner can unset admin");
        }

        GroupMember target = findMember(groupId, userId);
        if (target == null) {
            throw new BizException(404, "Member not found");
        }
        if (target.getRole() != 1) {
            throw new BizException(400, "Member is not an admin");
        }

        updateMemberRole(groupId, userId, 0);
        sendGroupNotification(groupId, SystemEvent.GROUP_ADMIN_UNSET,
                "「" + displayName(userId) + "」被取消群聊「" + groupName(group, groupId) + "」管理员",
                String.valueOf(groupId), String.valueOf(userId), String.valueOf(operatorId));
    }

    @CacheEvict(cacheNames = CacheNames.GROUP_MEMBERS, key = "#groupId")
    public void quitGroup(Long groupId, Long userId) {
        Group group = findById(groupId);
        if (group == null) {
            throw new BizException(404, "Group not found");
        }
        if (userId.equals(group.getOwnerId())) {
            throw new BizException(400, "Group owner cannot quit, please transfer ownership first");
        }
        groupMemberMapper.delete(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId));

        sendGroupNotification(groupId, SystemEvent.GROUP_QUIT,
                "「" + displayName(userId) + "」退出群聊「" + groupName(group, groupId) + "」",
                String.valueOf(groupId), String.valueOf(userId));
    }

    @Cacheable(cacheNames = CacheNames.GROUP_MEMBERS, key = "#groupId")
    public List<String> getMemberIds(Long groupId) {
        return groupMemberMapper.selectMemberIds(groupId)
                .stream()
                .map(String::valueOf)
                .collect(Collectors.toList());
    }

    public List<GroupMember> getMembers(Long groupId) {
        return groupMemberMapper.selectList(
            new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupId, groupId)
                .orderByDesc(GroupMember::getRole)
                .orderByAsc(GroupMember::getJoinedAt)
        );
    }

    public List<Group> getUserGroups(Long userId) {
        List<GroupMember> memberships = groupMemberMapper.selectList(
            new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getUserId, userId)
        );
        return memberships.stream()
                .map(m -> groupMapper.selectById(m.getGroupId()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public boolean isMember(Long groupId, Long userId) {
        return groupMemberMapper.selectCount(
            new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId)
        ) > 0;
    }

    public boolean isOwner(Long groupId, Long userId) {
        Group group = findById(groupId);
        return group != null && userId.equals(group.getOwnerId());
    }

    private GroupMember findMember(Long groupId, Long userId) {
        return groupMemberMapper.selectOne(
            new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId)
        );
    }

    @CacheEvict(cacheNames = CacheNames.GROUP_INFO, key = "#groupId")
    public void updateGroupName(Long groupId, String name, Long operatorId) {
        if (!isOwner(groupId, operatorId)) {
            throw new BizException(403, "Only group owner can update group name");
        }
        Group group = new Group();
        group.setId(groupId);
        group.setName(name);
        groupMapper.updateById(group);

        sendGroupNotification(groupId, SystemEvent.GROUP_RENAME,
                "群聊「" + groupId + "」已改名为「" + name + "」",
                String.valueOf(groupId), name, String.valueOf(operatorId));
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.GROUP_INFO, key = "#groupId"),
            @CacheEvict(cacheNames = CacheNames.GROUP_MEMBERS, key = "#groupId")
    })
    public void transferOwner(Long groupId, Long newOwnerId, Long currentOwnerId) {
        if (newOwnerId == null) {
            throw new BizException(400, "newOwnerId is required");
        }
        Group existing = groupMapper.selectForUpdate(groupId);
        if (existing == null) {
            throw new BizException(404, "Group not found");
        }
        if (!currentOwnerId.equals(existing.getOwnerId())) {
            throw new BizException(403, "Only group owner can transfer ownership");
        }
        if (newOwnerId.equals(currentOwnerId)) {
            throw new BizException(400, "Cannot transfer ownership to yourself");
        }
        if (findMember(groupId, newOwnerId) == null) {
            throw new BizException(404, "New owner is not a member");
        }

        Group group = new Group();
        group.setId(groupId);
        group.setOwnerId(newOwnerId);
        groupMapper.updateById(group);

        updateMemberRole(groupId, currentOwnerId, 1);
        updateMemberRole(groupId, newOwnerId, 2);

        sendGroupNotification(groupId, SystemEvent.GROUP_TRANSFER,
                "「" + displayName(newOwnerId) + "」成为群聊「"
                        + groupName(existing, groupId) + "」的新群主",
                String.valueOf(groupId), String.valueOf(newOwnerId), String.valueOf(currentOwnerId));
    }

    private void updateMemberRole(Long groupId, Long userId, int role) {
        GroupMember member = new GroupMember();
        member.setGroupId(groupId);
        member.setUserId(userId);
        member.setRole(role);
        groupMemberMapper.update(member, new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId));
    }

    public int getMemberCount(Long groupId) {
        return groupMemberMapper.selectCount(
            new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupId, groupId)
        ).intValue();
    }

    /**
     * 向群内所有成员发送 SYSTEM 通知（含操作者，便于多端同步刷新）。
     */
    private void sendGroupNotification(Long groupId, SystemEvent event, String text, String... args) {
        sendGroupNotificationTo(getMemberIds(groupId), event, text, args);
    }

    private void sendGroupNotificationTo(List<String> memberIds, SystemEvent event, String text, String... args) {
        String eventContent = event.format(args);
        for (String memberId : memberIds) {
            try {
                systemMessageService.sendNotification(Long.parseLong(memberId), text, eventContent);
            } catch (Exception e) {
                log.error("Failed to send {} notification to {}: {}", event.getType(), memberId, e.getMessage());
            }
        }
    }

    private String groupName(Group group, Long groupId) {
        return group != null && group.getName() != null && !group.getName().isBlank()
                ? group.getName() : String.valueOf(groupId);
    }

    private String displayName(Long userId) {
        User user = userService.findById(userId);
        if (user == null) {
            return String.valueOf(userId);
        }
        return user.getNickname() != null && !user.getNickname().isBlank()
                ? user.getNickname() : user.getUsername();
    }
}
