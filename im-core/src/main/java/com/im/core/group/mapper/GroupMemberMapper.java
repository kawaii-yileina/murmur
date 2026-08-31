package com.im.core.group.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.group.entity.GroupMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface GroupMemberMapper extends BaseMapper<GroupMember> {

    @Select("SELECT user_id FROM t_group_member WHERE group_id = #{groupId}")
    List<Long> selectMemberIds(Long groupId);
}
