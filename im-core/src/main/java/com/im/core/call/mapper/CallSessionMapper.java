package com.im.core.call.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.call.entity.CallSession;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CallSessionMapper extends BaseMapper<CallSession> {

    /** 幂等写入（call_id 唯一）：重复结束事件不产生重复记录 */
    @Insert("INSERT IGNORE INTO t_call_session "
            + "(id, call_id, caller_id, callee_id, media_type, status, started_at, answered_at, ended_at, duration_seconds, created_at) "
            + "VALUES (#{c.id}, #{c.callId}, #{c.callerId}, #{c.calleeId}, #{c.mediaType}, #{c.status}, "
            + "#{c.startedAt}, #{c.answeredAt}, #{c.endedAt}, #{c.durationSeconds}, #{c.createdAt})")
    int insertIgnore(@Param("c") CallSession callSession);
}
