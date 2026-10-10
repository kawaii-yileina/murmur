package com.im.core.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.core.outbox.entity.Outbox;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 领取与状态流转。
 *
 * 领取在调用方的短事务内执行：SELECT ... FOR UPDATE SKIP LOCKED 锁定候选行，
 * 随后置 PROCESSING + 租约，事务提交后锁释放，调用方再在事务外做网络发送。
 * 领取条件含会话有序窗口约束：同一会话内，仅当"更早的在途行（PENDING/PROCESSING）"少于 window 时才可领取，
 * 从而限制同会话在途条数、保证近似顺序，同时避免"每轮只能领取队头一条"导致的吞吐塌陷。
 * 注：DEAD 为终态，不计入在途，避免其永久阻塞后续消息。
 */
public interface OutboxMapper extends BaseMapper<Outbox> {

    @Select("SELECT o.id FROM t_outbox o "
            + "WHERE ((o.status = 'PENDING' AND o.next_retry_at <= #{now}) "
            + "    OR (o.status = 'PROCESSING' AND o.lease_until < #{now})) "
            + "  AND (SELECT COUNT(*) FROM t_outbox p "
            + "        WHERE p.conversation_id = o.conversation_id "
            + "          AND p.seq < o.seq AND p.status IN ('PENDING','PROCESSING')) < #{window} "
            + "ORDER BY o.seq ASC, o.id ASC "
            + "LIMIT #{batch} FOR UPDATE SKIP LOCKED")
    List<Long> selectClaimableIds(@Param("batch") int batch, @Param("window") int window,
                                  @Param("now") LocalDateTime now);

    @Update("<script>"
            + "UPDATE t_outbox SET status = 'PROCESSING', lease_until = #{leaseUntil}, updated_at = NOW() "
            + "WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int markProcessing(@Param("ids") List<Long> ids, @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("UPDATE t_outbox SET status = 'DONE', lease_until = NULL, last_error = NULL, updated_at = NOW() WHERE id = #{id}")
    int markDone(@Param("id") Long id);

    @Update("UPDATE t_outbox SET status = 'PENDING', retry_count = #{retryCount}, next_retry_at = #{nextRetryAt}, "
            + "lease_until = NULL, last_error = #{lastError}, updated_at = NOW() WHERE id = #{id}")
    int markRetry(@Param("id") Long id, @Param("retryCount") int retryCount,
                  @Param("nextRetryAt") LocalDateTime nextRetryAt, @Param("lastError") String lastError);

    @Update("UPDATE t_outbox SET status = 'DEAD', retry_count = #{retryCount}, lease_until = NULL, "
            + "last_error = #{lastError}, updated_at = NOW() WHERE id = #{id}")
    int markDead(@Param("id") Long id, @Param("retryCount") int retryCount, @Param("lastError") String lastError);

    @Select("SELECT COUNT(*) FROM t_outbox WHERE status = #{status}")
    long countByStatus(@Param("status") String status);

    @Select("SELECT COUNT(*) FROM t_outbox WHERE status = 'PENDING' AND next_retry_at <= #{now}")
    long countDuePending(@Param("now") LocalDateTime now);

    @Select("SELECT MIN(created_at) FROM t_outbox WHERE status = 'PENDING'")
    LocalDateTime oldestPending();
}
