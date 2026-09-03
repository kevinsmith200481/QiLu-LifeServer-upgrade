package com.qilu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qilu.entity.InboxDeliveryTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface InboxDeliveryTaskMapper extends BaseMapper<InboxDeliveryTask> {

    @Update("CREATE TABLE IF NOT EXISTS `inbox_delivery_task` ("
            + "`id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,"
            + "`task_no` varchar(64) NOT NULL,"
            + "`month_key` char(6) NOT NULL,"
            + "`message_id` bigint(20) UNSIGNED NOT NULL,"
            + "`target_type` varchar(16) NOT NULL,"
            + "`target_value` text,"
            + "`publish_status` varchar(16) NOT NULL DEFAULT 'PENDING',"
            + "`publish_attempts` int(11) NOT NULL DEFAULT 0,"
            + "`next_publish_time` datetime DEFAULT NULL,"
            + "`lease_owner` varchar(128) DEFAULT NULL,"
            + "`lease_until` datetime DEFAULT NULL,"
            + "`last_publish_time` datetime DEFAULT NULL,"
            + "`last_publish_error` varchar(512) DEFAULT NULL,"
            + "`delivery_status` varchar(16) NOT NULL DEFAULT 'WAITING',"
            + "`delivery_attempts` int(11) NOT NULL DEFAULT 0,"
            + "`next_delivery_time` datetime DEFAULT NULL,"
            + "`last_delivery_error` varchar(512) DEFAULT NULL,"
            + "`version` int(11) NOT NULL DEFAULT 0,"
            + "`create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,"
            + "`update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "PRIMARY KEY (`id`),"
            + "UNIQUE KEY `uk_task_no` (`task_no`),"
            + "KEY `idx_publish_ready` (`publish_status`, `next_publish_time`, `lease_until`, `id`),"
            + "KEY `idx_delivery_status` (`delivery_status`, `next_delivery_time`, `id`),"
            + "KEY `idx_message` (`month_key`, `message_id`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='inbox transactional outbox task'")
    void createTable();

    @Select("SELECT COUNT(1) FROM information_schema.columns WHERE table_schema = DATABASE() "
            + "AND table_name = 'inbox_delivery_task' "
            + "AND column_name IN ('publish_status', 'delivery_status', 'version')")
    int countRequiredOutboxColumns();

    @Select("SELECT * FROM inbox_delivery_task WHERE "
            + "delivery_status NOT IN ('SUCCESS', 'DEAD') AND ("
            + "publish_status = 'PENDING' "
            + "OR (publish_status = 'RETRY_WAIT' AND (next_publish_time IS NULL OR next_publish_time <= NOW())) "
            + "OR (publish_status = 'PUBLISHING' AND lease_until < NOW())) "
            + "ORDER BY id LIMIT #{limit}")
    List<InboxDeliveryTask> selectReadyForPublish(@Param("limit") int limit);

    @Update("UPDATE inbox_delivery_task SET publish_status = 'PUBLISHING', "
            + "publish_attempts = publish_attempts + 1, lease_owner = #{leaseOwner}, "
            + "lease_until = #{leaseUntil}, last_publish_error = NULL, version = version + 1 "
            + "WHERE id = #{id} AND version = #{version} AND delivery_status NOT IN ('SUCCESS', 'DEAD') AND ("
            + "publish_status = 'PENDING' "
            + "OR (publish_status = 'RETRY_WAIT' AND (next_publish_time IS NULL OR next_publish_time <= NOW())) "
            + "OR (publish_status = 'PUBLISHING' AND lease_until < NOW()))")
    int claimForPublish(@Param("id") Long id,
                        @Param("version") Integer version,
                        @Param("leaseOwner") String leaseOwner,
                        @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("UPDATE inbox_delivery_task SET publish_status = 'PUBLISHED', last_publish_time = NOW(), "
            + "last_publish_error = NULL, next_publish_time = NULL, lease_owner = NULL, lease_until = NULL, "
            + "version = version + 1 WHERE id = #{id} AND publish_status = 'PUBLISHING' "
            + "AND lease_owner = #{leaseOwner}")
    int markPublished(@Param("id") Long id,
                      @Param("leaseOwner") String leaseOwner);

    @Update("UPDATE inbox_delivery_task SET publish_status = #{publishStatus}, "
            + "last_publish_error = #{error}, next_publish_time = #{nextPublishTime}, "
            + "lease_owner = NULL, lease_until = NULL, version = version + 1 "
            + "WHERE id = #{id} AND publish_status = 'PUBLISHING' "
            + "AND lease_owner = #{leaseOwner}")
    int markPublishFailure(@Param("id") Long id,
                           @Param("leaseOwner") String leaseOwner,
                           @Param("publishStatus") String publishStatus,
                           @Param("nextPublishTime") LocalDateTime nextPublishTime,
                           @Param("error") String error);

    @Update("UPDATE inbox_delivery_task SET delivery_status = 'SUCCESS', publish_status = 'PUBLISHED', "
            + "delivery_attempts = delivery_attempts + 1, last_delivery_error = NULL, "
            + "next_delivery_time = NULL, next_publish_time = NULL, "
            + "lease_owner = NULL, lease_until = NULL, last_publish_time = COALESCE(last_publish_time, NOW()), "
            + "last_publish_error = NULL, version = version + 1 "
            + "WHERE id = #{id} AND delivery_status NOT IN ('SUCCESS', 'DEAD')")
    int markDeliverySuccess(@Param("id") Long id);

    @Update("UPDATE inbox_delivery_task SET delivery_status = 'RETRY_WAIT', "
            + "delivery_attempts = delivery_attempts + 1, next_delivery_time = #{retryTime}, "
            + "last_delivery_error = #{error}, publish_status = 'RETRY_WAIT', "
            + "next_publish_time = #{retryTime}, lease_owner = NULL, lease_until = NULL, version = version + 1 "
            + "WHERE id = #{id} AND delivery_status NOT IN ('SUCCESS', 'DEAD')")
    int markDeliveryRetry(@Param("id") Long id,
                          @Param("retryTime") LocalDateTime retryTime,
                          @Param("error") String error);

    @Update("UPDATE inbox_delivery_task SET delivery_status = 'DEAD', "
            + "delivery_attempts = delivery_attempts + 1, next_delivery_time = NULL, "
            + "last_delivery_error = #{error}, version = version + 1 "
            + "WHERE id = #{id} AND delivery_status NOT IN ('SUCCESS', 'DEAD')")
    int markDeliveryDead(@Param("id") Long id, @Param("error") String error);

    @Update("UPDATE inbox_delivery_task SET publish_status = 'PENDING', publish_attempts = 0, "
            + "next_publish_time = NOW(), lease_owner = NULL, lease_until = NULL, last_publish_error = NULL, "
            + "delivery_status = 'WAITING', delivery_attempts = 0, next_delivery_time = NULL, "
            + "last_delivery_error = NULL, version = version + 1 WHERE id = #{id} "
            + "AND (publish_status = 'DEAD' OR delivery_status = 'DEAD')")
    int resetDeadForManualRetry(@Param("id") Long id);

    @Select("SELECT COUNT(1) FROM inbox_delivery_task WHERE delivery_status NOT IN ('SUCCESS', 'DEAD')")
    long countBacklog();

    @Select("SELECT MIN(create_time) FROM inbox_delivery_task WHERE delivery_status NOT IN ('SUCCESS', 'DEAD')")
    LocalDateTime selectOldestPendingTime();
}
