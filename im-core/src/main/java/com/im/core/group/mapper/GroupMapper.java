package com.im.core.group.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.group.entity.Group;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface GroupMapper extends BaseMapper<Group> {

    /** 锁定群行，串行化管理员上限/群主变更等角色写操作 */
    @Select("SELECT * FROM t_group WHERE id = #{groupId} FOR UPDATE")
    Group selectForUpdate(@Param("groupId") Long groupId);
}
