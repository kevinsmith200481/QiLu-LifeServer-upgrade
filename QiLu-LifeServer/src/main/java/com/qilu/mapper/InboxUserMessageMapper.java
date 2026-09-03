package com.qilu.mapper;

import com.qilu.entity.InboxUserMessage;
import com.qilu.vo.InboxMessageVO;
import com.qilu.vo.InboxTypeCountVO;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.UpdateProvider;

import java.util.List;

public interface InboxUserMessageMapper {

    @Update("CREATE TABLE IF NOT EXISTS `${table}` ("
            + "`id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,"
            + "`month_key` char(6) NOT NULL,"
            + "`message_id` bigint(20) UNSIGNED NOT NULL,"
            + "`message_no` varchar(64) NOT NULL,"
            + "`user_id` bigint(20) UNSIGNED NOT NULL,"
            + "`message_type` varchar(32) NOT NULL,"
            + "`read_status` tinyint(1) NOT NULL DEFAULT 0,"
            + "`star_status` tinyint(1) NOT NULL DEFAULT 0,"
            + "`deleted` tinyint(1) NOT NULL DEFAULT 0,"
            + "`read_time` datetime DEFAULT NULL,"
            + "`create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,"
            + "`update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "PRIMARY KEY (`id`),"
            + "UNIQUE KEY `uk_user_message` (`user_id`, `message_id`),"
            + "KEY `idx_user_filter_cursor` (`user_id`, `deleted`, `message_type`, `read_status`, `star_status`, `id` DESC),"
            + "KEY `idx_user_create` (`user_id`, `create_time` DESC)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='monthly sharded inbox user copy'")
    void createUserMessageTable(@Param("table") String table);

    @InsertProvider(type = InboxUserMessageSqlProvider.class, method = "batchInsert")
    int batchInsert(@Param("table") String table, @Param("list") List<InboxUserMessage> list);

    @Select("<script>"
            + "SELECT user_id FROM `${table}` WHERE message_id = #{messageId} AND user_id IN "
            + "<foreach collection='userIds' item='userId' open='(' separator=',' close=')'>#{userId}</foreach>"
            + "</script>")
    List<Long> selectExistingUserIds(@Param("table") String table,
                                     @Param("messageId") Long messageId,
                                     @Param("userIds") List<Long> userIds);

    @Select("<script>"
            + "SELECT um.id AS cursorId, m.id AS messageId, m.message_no AS messageNo, m.message_type AS messageType, m.title, "
            + "m.summary, NULL AS content, m.business_type AS businessType, m.business_id AS businessId, "
            + "um.read_status AS readStatus, um.star_status AS starStatus, m.expire_time AS expireTime, um.create_time AS createTime "
            + "FROM `${userTable}` um INNER JOIN `${messageTable}` m ON um.message_id = m.id "
            + "WHERE um.user_id = #{userId} AND um.deleted = 0 AND m.status = 1 "
            + "AND (m.expire_time IS NULL OR m.expire_time > NOW()) "
            + "<if test='messageType != null and messageType != \"\"'>AND um.message_type = #{messageType} </if>"
            + "<if test='readStatus != null'>AND um.read_status = #{readStatus} </if>"
            + "<if test='starStatus != null'>AND um.star_status = #{starStatus} </if>"
            + "<if test='cursor != null'>AND um.id &lt; #{cursor} </if>"
            + "ORDER BY um.id DESC LIMIT #{limit}"
            + "</script>")
    List<InboxMessageVO> selectCursorPage(@Param("messageTable") String messageTable,
                                          @Param("userTable") String userTable,
                                          @Param("userId") Long userId,
                                          @Param("messageType") String messageType,
                                          @Param("readStatus") Integer readStatus,
                                          @Param("starStatus") Integer starStatus,
                                          @Param("cursor") Long cursor,
                                          @Param("limit") Integer limit);

    @Select("SELECT id, month_key AS monthKey, message_id AS messageId, message_no AS messageNo, user_id AS userId, "
            + "message_type AS messageType, read_status AS readStatus, star_status AS starStatus, deleted, "
            + "read_time AS readTime, create_time AS createTime, update_time AS updateTime "
            + "FROM `${table}` WHERE user_id = #{userId} AND message_id = #{messageId} AND deleted = 0")
    InboxUserMessage selectUserMessage(@Param("table") String table,
                                       @Param("userId") Long userId,
                                       @Param("messageId") Long messageId);

    @Select("SELECT message_type AS messageType, COUNT(1) AS count FROM `${table}` "
            + "WHERE user_id = #{userId} AND deleted = 0 AND read_status = 0 GROUP BY message_type")
    List<InboxTypeCountVO> countUnreadGroupByType(@Param("table") String table, @Param("userId") Long userId);

    @Select("SELECT id, month_key AS monthKey, message_id AS messageId, message_no AS messageNo, user_id AS userId, "
            + "message_type AS messageType, read_status AS readStatus, star_status AS starStatus, deleted, "
            + "read_time AS readTime, create_time AS createTime, update_time AS updateTime "
            + "FROM `${table}` WHERE user_id = #{userId} AND deleted = 0 AND read_status = 0")
    List<InboxUserMessage> selectUnreadUserMessages(@Param("table") String table, @Param("userId") Long userId);

    @Update("UPDATE `${table}` SET read_status = 1, read_time = NOW(), update_time = NOW() "
            + "WHERE user_id = #{userId} AND deleted = 0 AND read_status = 0")
    int updateAllReadStatus(@Param("table") String table, @Param("userId") Long userId);

    @UpdateProvider(type = InboxUserMessageSqlProvider.class, method = "updateReadStatus")
    int updateReadStatus(@Param("table") String table,
                         @Param("userId") Long userId,
                         @Param("messageIds") List<Long> messageIds,
                         @Param("readStatus") Integer readStatus);

    @UpdateProvider(type = InboxUserMessageSqlProvider.class, method = "updateStarStatus")
    int updateStarStatus(@Param("table") String table,
                         @Param("userId") Long userId,
                         @Param("messageIds") List<Long> messageIds,
                         @Param("starStatus") Integer starStatus);

    @UpdateProvider(type = InboxUserMessageSqlProvider.class, method = "deleteUserMessages")
    int deleteUserMessages(@Param("table") String table,
                           @Param("userId") Long userId,
                           @Param("messageIds") List<Long> messageIds);
}
