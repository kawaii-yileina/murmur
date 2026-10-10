package com.im.core.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.conversation.entity.ConversationSeq;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 权威会话序号读写。
 *
 * {@link #nextSeq(String)} 在调用方事务内执行，靠 INSERT ... ON DUPLICATE KEY UPDATE 的行锁
 * 使同一会话的并发事务串行化；事务回滚时序号递增一并回滚。
 */
public interface ConversationSeqMapper extends BaseMapper<ConversationSeq> {

    @Insert("INSERT INTO t_conversation_seq(conversation_id, last_seq, updated_at) "
            + "VALUES(#{conversationId}, 1, NOW()) "
            + "ON DUPLICATE KEY UPDATE last_seq = last_seq + 1, updated_at = NOW()")
    int bump(@Param("conversationId") String conversationId);

    @Select("SELECT last_seq FROM t_conversation_seq WHERE conversation_id = #{conversationId}")
    Long lastSeq(@Param("conversationId") String conversationId);
}
