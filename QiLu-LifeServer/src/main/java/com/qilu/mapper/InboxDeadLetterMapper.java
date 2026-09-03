package com.qilu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qilu.entity.InboxDeadLetter;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface InboxDeadLetterMapper extends BaseMapper<InboxDeadLetter> {

    @Update("CREATE TABLE IF NOT EXISTS `inbox_dead_letter` ("
            + "`id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,"
            + "`task_no` varchar(64) NOT NULL,"
            + "`month_key` char(6) NOT NULL,"
            + "`message_id` bigint(20) UNSIGNED NOT NULL,"
            + "`payload` text,"
            + "`error_msg` varchar(512) DEFAULT NULL,"
            + "`create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,"
            + "PRIMARY KEY (`id`),"
            + "UNIQUE KEY `uk_task_no` (`task_no`),"
            + "KEY `idx_message` (`month_key`, `message_id`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='inbox dead letter fallback'")
    void createTable();

    @Insert("INSERT INTO inbox_dead_letter(task_no, month_key, message_id, payload, error_msg, create_time) "
            + "VALUES(#{letter.taskNo}, #{letter.monthKey}, #{letter.messageId}, #{letter.payload}, "
            + "#{letter.errorMsg}, NOW()) ON DUPLICATE KEY UPDATE error_msg = VALUES(error_msg)")
    int upsertByTaskNo(@Param("letter") InboxDeadLetter letter);
}
