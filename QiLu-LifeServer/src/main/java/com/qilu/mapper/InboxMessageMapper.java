package com.qilu.mapper;

import com.qilu.entity.InboxMessage;
import com.qilu.vo.AdminInboxMessageVO;
import com.qilu.vo.InboxMessageVO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface InboxMessageMapper {

    @Update("CREATE TABLE IF NOT EXISTS `${table}` ("
            + "`id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,"
            + "`month_key` char(6) NOT NULL,"
            + "`message_no` varchar(64) NOT NULL,"
            + "`message_type` varchar(32) NOT NULL,"
            + "`target_type` varchar(16) NOT NULL,"
            + "`title` varchar(128) NOT NULL,"
            + "`content` text NOT NULL,"
            + "`summary` varchar(512) DEFAULT NULL,"
            + "`business_type` varchar(64) DEFAULT NULL,"
            + "`business_id` bigint(20) DEFAULT NULL,"
            + "`target_roles` varchar(255) DEFAULT NULL,"
            + "`status` tinyint(1) NOT NULL DEFAULT 1,"
            + "`sender_id` bigint(20) DEFAULT NULL,"
            + "`expire_time` datetime DEFAULT NULL,"
            + "`revoke_time` datetime DEFAULT NULL,"
            + "`create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,"
            + "`update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "PRIMARY KEY (`id`),"
            + "UNIQUE KEY `uk_message_no` (`message_no`),"
            + "KEY `idx_type_status_time` (`message_type`, `status`, `create_time`),"
            + "KEY `idx_expire_status` (`expire_time`, `status`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='monthly sharded inbox message master'")
    void createMessageTable(@Param("table") String table);

    @Insert("INSERT INTO `${table}` "
            + "(month_key, message_no, message_type, target_type, title, content, summary, business_type, "
            + "business_id, target_roles, status, sender_id, expire_time, create_time, update_time) "
            + "VALUES (#{message.monthKey}, #{message.messageNo}, #{message.messageType}, #{message.targetType}, "
            + "#{message.title}, #{message.content}, #{message.summary}, #{message.businessType}, #{message.businessId}, "
            + "#{message.targetRoles}, #{message.status}, #{message.senderId}, #{message.expireTime}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "message.id", keyColumn = "id")
    int insertMessage(@Param("table") String table, @Param("message") InboxMessage message);

    @Select("SELECT id, month_key AS monthKey, message_no AS messageNo, message_type AS messageType, "
            + "target_type AS targetType, title, content, summary, business_type AS businessType, business_id AS businessId, "
            + "target_roles AS targetRoles, status, sender_id AS senderId, expire_time AS expireTime, revoke_time AS revokeTime, "
            + "create_time AS createTime, update_time AS updateTime FROM `${table}` WHERE id = #{id}")
    InboxMessage selectById(@Param("table") String table, @Param("id") Long id);

    @Select("SELECT id, month_key AS monthKey, message_no AS messageNo, message_type AS messageType, "
            + "target_type AS targetType, title, content, summary, business_type AS businessType, business_id AS businessId, "
            + "target_roles AS targetRoles, status, sender_id AS senderId, expire_time AS expireTime, revoke_time AS revokeTime, "
            + "create_time AS createTime, update_time AS updateTime FROM `${table}` WHERE message_no = #{messageNo}")
    InboxMessage selectByMessageNo(@Param("table") String table, @Param("messageNo") String messageNo);

    @Select("SELECT m.id AS messageId, m.message_no AS messageNo, m.message_type AS messageType, m.title, "
            + "m.summary, m.content, m.business_type AS businessType, m.business_id AS businessId, "
            + "um.read_status AS readStatus, um.star_status AS starStatus, m.expire_time AS expireTime, um.create_time AS createTime "
            + "FROM `${userTable}` um INNER JOIN `${messageTable}` m ON um.message_id = m.id "
            + "WHERE um.user_id = #{userId} AND um.message_id = #{messageId} AND um.deleted = 0 AND m.status = 1 "
            + "AND (m.expire_time IS NULL OR m.expire_time > NOW())")
    InboxMessageVO selectUserDetail(@Param("messageTable") String messageTable,
                                    @Param("userTable") String userTable,
                                    @Param("userId") Long userId,
                                    @Param("messageId") Long messageId);

    @Update("UPDATE `${table}` SET status = 2, revoke_time = NOW() WHERE id = #{id} AND status = 1")
    int revoke(@Param("table") String table, @Param("id") Long id);

    @Select("<script>"
            + "SELECT month_key AS monthKey, id AS messageId, message_no AS messageNo, "
            + "message_type AS messageType, target_type AS targetType, title, summary, "
            + "business_type AS businessType, business_id AS businessId, sender_id AS senderId, "
            + "expire_time AS expireTime, create_time AS createTime "
            + "FROM `${table}` "
            + "WHERE status = 1 AND (expire_time IS NULL OR expire_time &gt; NOW()) "
            + "<if test='managerScope'>AND message_type != 'EXCEPTION_ALERT' </if>"
            + "ORDER BY id DESC LIMIT #{limit}"
            + "</script>")
    List<AdminInboxMessageVO> selectActiveSentMessages(@Param("table") String table,
                                                       @Param("managerScope") boolean managerScope,
                                                       @Param("limit") Integer limit);

    @Update("UPDATE `${table}` SET status = 3 WHERE status = 1 AND expire_time IS NOT NULL AND expire_time <= NOW()")
    int expireMessages(@Param("table") String table);
}
