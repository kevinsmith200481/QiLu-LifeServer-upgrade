package com.qilu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qilu.entity.AppointmentFailureLog;
import org.apache.ibatis.annotations.Update;

public interface AppointmentFailureLogMapper extends BaseMapper<AppointmentFailureLog> {

    @Update("CREATE TABLE IF NOT EXISTS `appointment_failure_log` ("
            + "`id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,"
            + "`failure_type` varchar(64) NOT NULL,"
            + "`status` varchar(32) NOT NULL,"
            + "`event_id` varchar(64) DEFAULT NULL,"
            + "`order_id` bigint(20) UNSIGNED DEFAULT NULL,"
            + "`user_id` bigint(20) UNSIGNED DEFAULT NULL,"
            + "`slot_id` bigint(20) UNSIGNED DEFAULT NULL,"
            + "`service_point_id` bigint(20) UNSIGNED DEFAULT NULL,"
            + "`reason` varchar(512) DEFAULT NULL,"
            + "`payload` text,"
            + "`create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,"
            + "PRIMARY KEY (`id`),"
            + "UNIQUE KEY `uk_type_event` (`failure_type`, `event_id`),"
            + "KEY `idx_type_status_time` (`failure_type`, `status`, `create_time`),"
            + "KEY `idx_order_id` (`order_id`),"
            + "KEY `idx_slot_user` (`slot_id`, `user_id`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='appointment compensation and dead letter log'")
    void createTable();
}
