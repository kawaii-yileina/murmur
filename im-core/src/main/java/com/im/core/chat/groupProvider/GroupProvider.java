package com.im.core.chat.groupProvider;

import com.elaina.chat.core.group.GroupMemberProvider;
import com.im.core.group.mapper.GroupMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ClassName: GroupProvider
 * Package: com.im.core.chat.groupProvider
 * Description:
 *
 * @Author: 灰之魔女-伊蕾娜
 * @Create: 2026/9/16 20:48
 * @Version:
 */
@Component
@RequiredArgsConstructor
public class GroupProvider implements GroupMemberProvider {

    private final GroupMemberMapper memberMapper;

    @Override
    public List<String> getMembers(String groupId) {
        return memberMapper.selectMemberIds(Long.parseLong(groupId)).stream().map(String::valueOf).toList();
    }
}
