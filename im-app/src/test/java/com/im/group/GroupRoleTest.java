package com.im.group;

import com.im.common.exception.BizException;
import com.im.core.group.entity.Group;
import com.im.core.group.entity.GroupMember;
import com.im.core.group.mapper.GroupMapper;
import com.im.core.group.mapper.GroupMemberMapper;
import com.im.core.group.service.GroupService;
import com.im.core.message.service.SystemNotificationService;
import com.im.core.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 群角色权限：仅群主可设/取消管理员、管理员只能移除普通成员、群名仅群主可改。
 */
class GroupRoleTest {

    private static final Long GROUP_ID = 10L;

    private GroupMapper groupMapper;
    private GroupMemberMapper memberMapper;
    private SystemNotificationService systemNotificationService;
    private UserService userService;
    private GroupService service;
    private Group group;

    @BeforeEach
    void setUp() {
        groupMapper = mock(GroupMapper.class);
        memberMapper = mock(GroupMemberMapper.class);
        systemNotificationService = mock(SystemNotificationService.class);
        userService = mock(UserService.class);
        service = new GroupService(groupMapper, memberMapper, systemNotificationService, userService);
        ReflectionTestUtils.setField(service, "maxAdmins", 3);

        group = new Group();
        group.setId(GROUP_ID);
        group.setName("test-group");
        group.setOwnerId(1L);
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        when(groupMapper.selectForUpdate(GROUP_ID)).thenReturn(group);
        when(memberMapper.selectMemberIds(GROUP_ID)).thenReturn(List.of(1L, 2L, 3L));
    }

    private GroupMember member(Long userId, int role) {
        GroupMember member = new GroupMember();
        member.setGroupId(GROUP_ID);
        member.setUserId(userId);
        member.setRole(role);
        return member;
    }

    @Test
    void ownerCanSetAndUnsetAdmin() {
        GroupMember target = member(2L, 0);
        when(memberMapper.selectOne(any())).thenReturn(target);
        when(memberMapper.selectCount(any())).thenReturn(0L);

        service.setAdmin(GROUP_ID, 2L, 1L);
        verify(memberMapper).update(any(GroupMember.class), any());

        target.setRole(1);
        service.unsetAdmin(GROUP_ID, 2L, 1L);
        verify(memberMapper, org.mockito.Mockito.times(2)).update(any(GroupMember.class), any());
    }

    @Test
    void nonOwnerCannotSetAdmin() {
        assertThatThrownBy(() -> service.setAdmin(GROUP_ID, 2L, 2L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        verify(memberMapper, never()).update(any(GroupMember.class), any());
    }

    @Test
    void adminLimitIsEnforced() {
        when(memberMapper.selectOne(any())).thenReturn(member(2L, 0));
        when(memberMapper.selectCount(any())).thenReturn(3L);

        assertThatThrownBy(() -> service.setAdmin(GROUP_ID, 2L, 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Admin limit");
    }

    @Test
    void ownerCannotBeSetAsAdmin() {
        assertThatThrownBy(() -> service.setAdmin(GROUP_ID, 1L, 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("owner");
    }

    @Test
    void adminCanRemoveRegularMember() {
        when(memberMapper.selectOne(any())).thenReturn(member(3L, 0), member(2L, 1));

        service.removeMember(GROUP_ID, 3L, 2L);
        verify(memberMapper).delete(any());
    }

    @Test
    void adminCannotRemoveAdmin() {
        when(memberMapper.selectOne(any())).thenReturn(member(3L, 1), member(2L, 1));

        assertThatThrownBy(() -> service.removeMember(GROUP_ID, 3L, 2L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        verify(memberMapper, never()).delete(any());
    }

    @Test
    void adminCannotRemoveOwner() {
        when(memberMapper.selectOne(any())).thenReturn(member(1L, 2), member(2L, 1));

        assertThatThrownBy(() -> service.removeMember(GROUP_ID, 1L, 2L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("owner");
    }

    @Test
    void ownerCanRemoveAdmin() {
        when(memberMapper.selectOne(any())).thenReturn(member(3L, 1));

        service.removeMember(GROUP_ID, 3L, 1L);
        verify(memberMapper).delete(any());
    }

    @Test
    void regularMemberCannotRemoveOther() {
        when(memberMapper.selectOne(any())).thenReturn(member(3L, 0), member(2L, 0));

        assertThatThrownBy(() -> service.removeMember(GROUP_ID, 3L, 2L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(403));
    }

    @Test
    void onlyOwnerCanRenameGroup() {
        assertThatThrownBy(() -> service.updateGroupName(GROUP_ID, "renamed", 2L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(403));

        service.updateGroupName(GROUP_ID, "renamed", 1L);
        verify(groupMapper).updateById(any(Group.class));
    }

    @Test
    void onlyOwnerCanUnsetAdmin() {
        when(memberMapper.selectOne(any())).thenReturn(member(2L, 1));

        assertThatThrownBy(() -> service.unsetAdmin(GROUP_ID, 2L, 2L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(403));
    }

    @Test
    void groupNotFoundIsRejected() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.removeMember(GROUP_ID, 3L, 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Group not found");
    }

    @Test
    void duplicateSetAdminIsIdempotent() {
        when(memberMapper.selectOne(any())).thenReturn(member(2L, 1));

        service.setAdmin(GROUP_ID, 2L, 1L);
        verify(memberMapper, never()).update(any(GroupMember.class), any());
        verify(memberMapper, never()).selectCount(any());
        verifyNoInteractions(systemNotificationService);
    }

    @Test
    void unsetNonAdminIsRejected() {
        when(memberMapper.selectOne(any())).thenReturn(member(2L, 0));

        assertThatThrownBy(() -> service.unsetAdmin(GROUP_ID, 2L, 1L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(400));
    }

    @Test
    void transferOwnerUpdatesBothRoles() {
        when(memberMapper.selectOne(any())).thenReturn(member(2L, 0));

        service.transferOwner(GROUP_ID, 2L, 1L);

        verify(groupMapper).updateById(any(Group.class));
        verify(memberMapper, org.mockito.Mockito.times(2)).update(any(GroupMember.class), any());
    }

    @Test
    void cannotTransferToNonMember() {
        when(memberMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.transferOwner(GROUP_ID, 99L, 1L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(404));
        verify(groupMapper, never()).updateById(any(Group.class));
    }

    @Test
    void cannotTransferToSelf() {
        assertThatThrownBy(() -> service.transferOwner(GROUP_ID, 1L, 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("yourself");
    }
}
