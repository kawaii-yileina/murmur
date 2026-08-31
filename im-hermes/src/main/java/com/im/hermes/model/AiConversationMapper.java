package com.im.hermes.model;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AiConversationMapper extends BaseMapper<AiConversation> {
    
    @Select("SELECT * FROM t_ai_conversation WHERE user_id = #{userId} AND session_id = #{sessionId} ORDER BY created_at ASC")
    List<AiConversation> findByUserIdAndSessionId(Long userId, String sessionId);
    
    @Select("SELECT * FROM t_ai_conversation WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT #{limit}")
    List<AiConversation> findRecentByUserId(Long userId, int limit);

    @Select("SELECT * FROM t_ai_conversation WHERE user_id = #{userId}")
    List<AiConversation> findByUserId(Long userId);
}
