package com.im.core.group.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.elaina.chat.core.message.ChatMessage;
import com.elaina.chat.core.gateway.MessageGateway;
import com.im.common.constant.ChatConstants;
import com.im.common.enums.SystemEvent;
import com.im.common.exception.BizException;
import com.im.core.group.entity.Group;
import com.im.core.group.entity.GroupMember;
import com.im.core.group.mapper.GroupMapper;
import com.im.core.group.mapper.GroupMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final MessageGateway messageGateway;

    public Group createGroup(String name, Long ownerId) {
        Group group = new Group();
        group.setName(name);
        group.setOwnerId(ownerId);
        groupMapper.insert(group);

        addMember(group.getId(), ownerId, 2);
        return group;
    }

    public Group findById(Long groupId) {
        return groupMapper.selectById(groupId);
    }

    public void addMember(Long groupId, Long userId, int role) {
        if (isMember(groupId, userId)) {
            throw new BizException("User is already a member of this group");
        }
        GroupMember member = new GroupMember();
        member.setGroupId(groupId);
        member.setUserId(userId);
        member.setRole(role);
        member.setJoinedAt(LocalDateTime.now());
        groupMemberMapper.insert(member);

        sendGroupNotification(groupId, SystemEvent.GROUP_ADD, String.valueOf(userId));
    }

    @Transactional
    public void removeMember(Long groupId, Long userId, Long operatorId) {
        Group group = findById(groupId);
        if (group == null) {
            throw new BizException("Group not found");
        }
        
        if (userId.equals(group.getOwnerId())) {
            throw new BizException("Cannot remove group owner");
        }
        
        if (!userId.equals(operatorId) && !isOwner(groupId, operatorId)) {
            throw new BizException("No permission to remove member");
        }
        
        groupMemberMapper.delete(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId));

        sendGroupNotification(groupId, SystemEvent.GROUP_KICK, String.valueOf(userId), String.valueOf(operatorId));
    }

    public void quitGroup(Long groupId, Long userId) {
        Group group = findById(groupId);
        if (group == null) {
            throw new BizException("Group not found");
        }
        if (userId.equals(group.getOwnerId())) {
            throw new BizException("Group owner cannot quit, please transfer ownership first");
        }
        groupMemberMapper.delete(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId));

        sendGroupNotification(groupId, SystemEvent.GROUP_QUIT, String.valueOf(userId));
    }

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
                .orderByAsc(GroupMember::getRole)
                .orderByDesc(GroupMember::getJoinedAt)
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

    public boolean isAdmin(Long groupId, Long userId) {
        GroupMember member = groupMemberMapper.selectOne(
            new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId)
        );
        return member != null && member.getRole() >= 1;
    }

    public void updateGroupName(Long groupId, String name, Long operatorId) {
        if (!isOwner(groupId, operatorId) && !isAdmin(groupId, operatorId)) {
            throw new BizException("No permission to update group name");
        }
        Group group = new Group();
        group.setId(groupId);
        group.setName(name);
        groupMapper.updateById(group);

        sendGroupNotification(groupId, SystemEvent.GROUP_RENAME, name, String.valueOf(operatorId));
    }

    public void transferOwner(Long groupId, Long newOwnerId, Long currentOwnerId) {
        if (!isOwner(groupId, currentOwnerId)) {
            throw new BizException("Only group owner can transfer ownership");
        }
        
        Group group = new Group();
        group.setId(groupId);
        group.setOwnerId(newOwnerId);
        groupMapper.updateById(group);
        
        updateMemberRole(groupId, currentOwnerId, 1);
        updateMemberRole(groupId, newOwnerId, 2);

        sendGroupNotification(groupId, SystemEvent.GROUP_TRANSFER, String.valueOf(newOwnerId), String.valueOf(currentOwnerId));
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
     * 向群内所有成员发送 SYSTEM通知（排除触发操作的用户）。
     */
    private void sendGroupNotification(Long groupId, SystemEvent event, String... args) {
        List<String> memberIds = getMemberIds(groupId);
        String content = event.format(args);
        for (String memberId : memberIds) {
            try {
                ChatMessage msg = ChatMessage.builder()
                        .type(ChatConstants.SYSTEM_USER)
                        .from(ChatConstants.SYSTEM_USER)
                        .to(memberId)
                        .group(false)
                        .content(content)
                        .build();
                // 三参数：不走持久化拦截器，SYSTEM通知不应写入 t_message/t_conversation
                messageGateway.sendToUser(memberId, msg, false);
            } catch (Exception e) {
                log.error("Failed to send {} notification to {}: {}", event.getType(), memberId, e.getMessage());
            }
        }
    }
}
