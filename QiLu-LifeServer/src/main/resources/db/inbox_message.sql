SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 收件箱模块固定表：发布状态与用户副本投递状态必须分开表达。
DROP TABLE IF EXISTS `inbox_delivery_task`;
CREATE TABLE `inbox_delivery_task` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_no` varchar(64) NOT NULL COMMENT '全局唯一投递任务号',
  `month_key` char(6) NOT NULL COMMENT '消息分表月份 yyyyMM',
  `message_id` bigint(20) UNSIGNED NOT NULL COMMENT '月份消息表主键',
  `target_type` varchar(16) NOT NULL COMMENT 'ALL/USER/ROLE',
  `target_value` text COMMENT '用户ID或角色列表，逗号分隔',
  `publish_status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PUBLISHING/PUBLISHED/RETRY_WAIT/DEAD',
  `publish_attempts` int(11) NOT NULL DEFAULT 0,
  `next_publish_time` datetime DEFAULT NULL,
  `lease_owner` varchar(128) DEFAULT NULL,
  `lease_until` datetime DEFAULT NULL,
  `last_publish_time` datetime DEFAULT NULL,
  `last_publish_error` varchar(512) DEFAULT NULL,
  `delivery_status` varchar(16) NOT NULL DEFAULT 'WAITING' COMMENT 'WAITING/SUCCESS/RETRY_WAIT/DEAD',
  `delivery_attempts` int(11) NOT NULL DEFAULT 0,
  `next_delivery_time` datetime DEFAULT NULL,
  `last_delivery_error` varchar(512) DEFAULT NULL,
  `version` int(11) NOT NULL DEFAULT 0,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_no` (`task_no`),
  KEY `idx_publish_ready` (`publish_status`, `next_publish_time`, `lease_until`, `id`),
  KEY `idx_delivery_status` (`delivery_status`, `next_delivery_time`, `id`),
  KEY `idx_message` (`month_key`, `message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='inbox async delivery task';

DROP TABLE IF EXISTS `inbox_dead_letter`;
CREATE TABLE `inbox_dead_letter` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_no` varchar(64) NOT NULL,
  `month_key` char(6) NOT NULL,
  `message_id` bigint(20) UNSIGNED NOT NULL,
  `payload` text,
  `error_msg` varchar(512) DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_no` (`task_no`),
  KEY `idx_message` (`month_key`, `message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='inbox dead letter fallback';

-- 月份分表示例：将 202607 替换为目标月份。应用启动时统一初始化当前月和下月。
DROP TABLE IF EXISTS `inbox_message_202607`;
CREATE TABLE `inbox_message_202607` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `month_key` char(6) NOT NULL,
  `message_no` varchar(64) NOT NULL,
  `message_type` varchar(32) NOT NULL COMMENT 'SYSTEM_NOTICE/BUSINESS_REMINDER/APPROVAL_NOTICE/EXCEPTION_ALERT/SITE_REPLY',
  `target_type` varchar(16) NOT NULL COMMENT 'ALL/USER/ROLE',
  `title` varchar(128) NOT NULL,
  `content` text NOT NULL,
  `summary` varchar(512) DEFAULT NULL,
  `business_type` varchar(64) DEFAULT NULL,
  `business_id` bigint(20) DEFAULT NULL,
  `target_roles` varchar(255) DEFAULT NULL,
  `status` tinyint(1) NOT NULL DEFAULT 1 COMMENT '1正常 2撤回 3过期',
  `sender_id` bigint(20) DEFAULT NULL,
  `expire_time` datetime DEFAULT NULL,
  `revoke_time` datetime DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_message_no` (`message_no`),
  KEY `idx_type_status_time` (`message_type`, `status`, `create_time`),
  KEY `idx_expire_status` (`expire_time`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='monthly sharded inbox message master';

DROP TABLE IF EXISTS `inbox_user_message_202607`;
CREATE TABLE `inbox_user_message_202607` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `month_key` char(6) NOT NULL,
  `message_id` bigint(20) UNSIGNED NOT NULL,
  `message_no` varchar(64) NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `message_type` varchar(32) NOT NULL,
  `read_status` tinyint(1) NOT NULL DEFAULT 0 COMMENT '0未读 1已读',
  `star_status` tinyint(1) NOT NULL DEFAULT 0 COMMENT '0未收藏 1已收藏',
  `deleted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '0正常 1用户删除',
  `read_time` datetime DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_message` (`user_id`, `message_id`),
  KEY `idx_user_filter_cursor` (`user_id`, `deleted`, `message_type`, `read_status`, `star_status`, `id` DESC),
  KEY `idx_user_create` (`user_id`, `create_time` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='monthly sharded inbox user copy';

-- 冷数据归档表。建议保留近 6 个月热分表，低峰期将更早月份 INSERT 到 archive 后删除对应月份表。
DROP TABLE IF EXISTS `inbox_message_archive`;
CREATE TABLE `inbox_message_archive` LIKE `inbox_message_202607`;

DROP TABLE IF EXISTS `inbox_user_message_archive`;
CREATE TABLE `inbox_user_message_archive` LIKE `inbox_user_message_202607`;

-- 归档模板：
-- INSERT INTO inbox_message_archive SELECT * FROM inbox_message_202601;
-- INSERT INTO inbox_user_message_archive SELECT * FROM inbox_user_message_202601;
-- DROP TABLE inbox_message_202601;
-- DROP TABLE inbox_user_message_202601;

-- 索引设计说明：
-- 1. uk_message_no/uk_user_message：支撑消息幂等和 MQ 至少一次投递下的重复消费防护。
-- 2. idx_user_filter_cursor：覆盖用户收件箱筛选 + 游标分页，避免 offset 深分页。
-- 3. idx_expire_status：支撑过期消息定时下架扫描。
-- 4. idx_publish_ready/idx_delivery_status：分别支撑 relay 扫描与消费状态治理。

SET FOREIGN_KEY_CHECKS = 1;
