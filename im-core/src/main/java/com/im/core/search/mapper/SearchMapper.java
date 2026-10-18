package com.im.core.search.mapper;

import com.im.core.search.dto.ContactRow;
import com.im.core.search.dto.GroupRow;
import com.im.core.search.dto.MemberRow;
import com.im.core.search.dto.MessageRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 聚合搜索查询。关键词由 Service 转义后以参数绑定，避免 LIKE 通配符注入。
 */
@Mapper
public interface SearchMapper {

    @Select("""
            SELECT u.id AS userId, u.username, u.nickname, u.avatar, u.signature,
                   CASE WHEN f.id IS NULL THEN 0 ELSE 1 END AS isFriend
            FROM t_user u
            LEFT JOIN t_friend f ON f.user_id = #{me} AND f.friend_id = u.id
            WHERE u.deleted = 0 AND u.id != #{me}
              AND (u.nickname LIKE #{like} OR u.username LIKE #{like})
            ORDER BY isFriend DESC, u.nickname ASC
            LIMIT #{limit}
            """)
    List<ContactRow> searchContacts(@Param("me") Long me, @Param("like") String like,
                                    @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
            FROM t_user u
            WHERE u.deleted = 0 AND u.id != #{me}
              AND (u.nickname LIKE #{like} OR u.username LIKE #{like})
            """)
    long countContacts(@Param("me") Long me, @Param("like") String like);

    @Select("""
            SELECT g.id, g.name, g.avatar, g.owner_id AS ownerId
            FROM t_group g
            JOIN t_group_member gm ON gm.group_id = g.id AND gm.user_id = #{me}
            WHERE g.name LIKE #{like}
            ORDER BY g.name ASC
            LIMIT #{limit}
            """)
    List<GroupRow> searchGroups(@Param("me") Long me, @Param("like") String like,
                                @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
            FROM t_group g
            JOIN t_group_member gm ON gm.group_id = g.id AND gm.user_id = #{me}
            WHERE g.name LIKE #{like}
            """)
    long countGroups(@Param("me") Long me, @Param("like") String like);

    @Select("""
            SELECT u.id AS userId, u.username, u.nickname, u.avatar,
                   gm.group_id AS groupId, g.name AS groupName, gm.role
            FROM t_group_member gm
            JOIN t_group_member mine ON mine.group_id = gm.group_id AND mine.user_id = #{me}
            JOIN t_group g ON g.id = gm.group_id
            JOIN t_user u ON u.id = gm.user_id
            WHERE u.deleted = 0
              AND gm.user_id != #{me}
              AND (u.nickname LIKE #{like} OR u.username LIKE #{like})
            ORDER BY gm.role DESC, u.nickname ASC
            LIMIT #{limit}
            """)
    List<MemberRow> searchMembers(@Param("me") Long me, @Param("like") String like,
                                  @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
            FROM t_group_member gm
            JOIN t_group_member mine ON mine.group_id = gm.group_id AND mine.user_id = #{me}
            JOIN t_user u ON u.id = gm.user_id
            WHERE u.deleted = 0
              AND gm.user_id != #{me}
              AND (u.nickname LIKE #{like} OR u.username LIKE #{like})
            """)
    long countMembers(@Param("me") Long me, @Param("like") String like);

    @Select("""
            SELECT m.msg_id AS msgId, m.seq, m.chat_type AS chatType,
                   CASE WHEN m.sender_id = #{me} THEN m.receiver_id ELSE m.sender_id END AS targetId,
                   m.sender_id AS senderId,
                   COALESCE(NULLIF(su.nickname, ''), su.username, 'Unknown') AS senderName,
                   NULL AS groupName, m.content, m.created_at AS createdAt
            FROM t_message m
            LEFT JOIN t_user su ON su.id = m.sender_id
            WHERE m.chat_type = 0 AND m.msg_type = 'TEXT'
              AND (m.sender_id = #{me} OR m.receiver_id = #{me})
              AND m.content LIKE #{like}
            ORDER BY m.created_at DESC
            LIMIT #{limit}
            """)
    List<MessageRow> searchPrivateMessages(@Param("me") Long me, @Param("like") String like,
                                           @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*) FROM (
              SELECT 1
              FROM t_message m
              WHERE m.chat_type = 0 AND m.msg_type = 'TEXT'
                AND (m.sender_id = #{me} OR m.receiver_id = #{me})
                AND m.content LIKE #{like}
              LIMIT 100
            ) capped
            """)
    long countPrivateMessages(@Param("me") Long me, @Param("like") String like);

    @Select("""
            <script>
            SELECT m.msg_id AS msgId, m.seq, m.chat_type AS chatType,
                   m.receiver_id AS targetId, m.sender_id AS senderId,
                   COALESCE(NULLIF(su.nickname, ''), su.username, 'Unknown') AS senderName,
                   g.name AS groupName, m.content, m.created_at AS createdAt
            FROM t_message m
            JOIN t_group_member gm ON gm.group_id = m.receiver_id AND gm.user_id = #{me}
            LEFT JOIN t_user su ON su.id = m.sender_id
            LEFT JOIN t_group g ON g.id = m.receiver_id
            WHERE m.chat_type = 1 AND m.msg_type = 'TEXT'
              AND m.content LIKE #{like}
              <if test="groupId != null">AND m.receiver_id = #{groupId}</if>
            ORDER BY m.created_at DESC
            LIMIT #{limit}
            </script>
            """)
    List<MessageRow> searchGroupMessages(@Param("me") Long me, @Param("like") String like,
                                         @Param("groupId") Long groupId, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM (
              SELECT 1
              FROM t_message m
              JOIN t_group_member gm ON gm.group_id = m.receiver_id AND gm.user_id = #{me}
              WHERE m.chat_type = 1 AND m.msg_type = 'TEXT'
                AND m.content LIKE #{like}
                <if test="groupId != null">AND m.receiver_id = #{groupId}</if>
              LIMIT 100
            ) capped
            </script>
            """)
    long countGroupMessages(@Param("me") Long me, @Param("like") String like,
                            @Param("groupId") Long groupId);
}
