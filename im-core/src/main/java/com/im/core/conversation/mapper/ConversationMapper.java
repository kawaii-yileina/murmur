package com.im.core.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.conversation.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}
