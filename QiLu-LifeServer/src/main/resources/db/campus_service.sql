SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- Role field for the existing tb_user login table.
-- Existing deployments can run this once before enabling /admin authorization.
ALTER TABLE `tb_user` ADD COLUMN `role` varchar(32) DEFAULT 'student';

DROP TABLE IF EXISTS `service_category`;
CREATE TABLE `service_category` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` varchar(64) NOT NULL,
  `icon` varchar(255) DEFAULT NULL,
  `sort` int(11) DEFAULT 0,
  `status` tinyint(1) DEFAULT 1 COMMENT '1 reserved 2 canceled 3 finished 4 expired 5 no_show',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS `service_point`;
CREATE TABLE `service_point` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` varchar(128) NOT NULL,
  `category_id` bigint(20) UNSIGNED NOT NULL,
  `manager_id` bigint(20) UNSIGNED DEFAULT NULL,
  `cover_image` varchar(1024) DEFAULT NULL,
  `area` varchar(128) DEFAULT NULL,
  `address` varchar(255) NOT NULL,
  `x` double NOT NULL,
  `y` double NOT NULL,
  `open_hours` varchar(64) DEFAULT NULL,
  `phone` varchar(32) DEFAULT NULL,
  `description` varchar(1024) DEFAULT NULL,
  `status` tinyint(1) DEFAULT 1,
  `score` int(11) DEFAULT 50,
  `service_count` int(11) DEFAULT 0,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_category_id` (`category_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `service_category` (`id`, `name`, `icon`, `sort`, `status`) VALUES
(1, '校园餐饮', '/imgs/icons/default-icon.png', 1, 1),
(2, '快递取件', '/imgs/icons/default-icon.png', 2, 1),
(3, '打印服务', '/imgs/icons/default-icon.png', 3, 1),
(4, '维修服务', '/imgs/icons/default-icon.png', 4, 1),
(5, '咨询服务', '/imgs/icons/default-icon.png', 5, 1);

INSERT INTO `service_point` (`id`, `name`, `category_id`, `area`, `address`, `x`, `y`, `open_hours`, `phone`, `description`, `status`, `score`, `service_count`) VALUES
(1, '一号食堂服务台', 1, '东校区', '一号食堂一楼', 117.1201, 36.6812, '07:00-20:00', '0531-100001', '餐饮窗口咨询和校园卡遗失协助。', 1, 48, 320),
(2, '校园快递站', 2, '学生中心', '学生中心北门', 117.1216, 36.6825, '09:00-21:00', '0531-100002', '提供快递取件和包裹异常处理。', 1, 47, 860),
(3, '图书馆打印点', 3, '图书馆', '图书馆二楼', 117.1193, 36.6808, '08:30-22:00', '0531-100003', '提供自助打印和装订支持。', 1, 46, 540),
(4, '宿舍维修中心', 4, '宿舍区', '宿舍区服务办公室', 117.1188, 36.6831, '08:00-18:00', '0531-100004', '处理宿舍水电、门窗维修问题。', 1, 45, 430),
(5, '就业咨询室', 5, '行政楼', '行政楼 305', 117.1224, 36.6802, '09:00-17:00', '0531-100005', '提供简历修改和就业咨询预约。', 1, 49, 210);

UPDATE `service_point` SET `manager_id` = (SELECT `id` FROM `tb_user` WHERE `phone` = '13456789011') WHERE `id` IN (4, 5);

DROP TABLE IF EXISTS `appointment_slot`;
CREATE TABLE `appointment_slot` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `service_point_id` bigint(20) UNSIGNED NOT NULL,
  `title` varchar(128) NOT NULL,
  `description` varchar(512) DEFAULT NULL,
  `total_quota` int(11) NOT NULL DEFAULT 0,
  `available_quota` int(11) NOT NULL DEFAULT 0,
  `start_time` datetime NOT NULL,
  `end_time` datetime NOT NULL,
  `status` tinyint(1) DEFAULT 1,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_service_point_id` (`service_point_id`),
  KEY `idx_status_start_time` (`status`, `start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS `appointment_order`;
CREATE TABLE `appointment_order` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `slot_id` bigint(20) UNSIGNED NOT NULL,
  `service_point_id` bigint(20) UNSIGNED DEFAULT NULL,
  `status` tinyint(1) DEFAULT 1,
  `active_slot_id` bigint(20) UNSIGNED GENERATED ALWAYS AS (CASE WHEN `status` = 1 THEN `slot_id` ELSE NULL END) STORED,
  `remark` varchar(512) DEFAULT NULL,
  `internal_remark` varchar(512) DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `cancel_time` datetime DEFAULT NULL,
  `finish_time` datetime DEFAULT NULL,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_slot_user` (`slot_id`, `user_id`),
  KEY `idx_status_slot` (`status`, `slot_id`),
  UNIQUE KEY `uk_user_active_slot` (`user_id`, `active_slot_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS `appointment_consistency_repair`;
CREATE TABLE `appointment_consistency_repair` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` bigint(20) UNSIGNED NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `slot_id` bigint(20) UNSIGNED NOT NULL,
  `repair_type` varchar(64) NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'PENDING',
  `release_redis_quota` tinyint(1) NOT NULL DEFAULT 1,
  `attempts` int(11) NOT NULL DEFAULT 0,
  `next_retry_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `last_error` varchar(512) DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_repair_type` (`order_id`, `repair_type`),
  KEY `idx_status_retry` (`status`, `next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='appointment DB and Redis consistency repair tasks';

DROP TABLE IF EXISTS `appointment_failure_log`;
CREATE TABLE `appointment_failure_log` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `failure_type` varchar(64) NOT NULL COMMENT 'ASYNC_ORDER_REJECTED / NOTIFICATION_DEAD',
  `status` varchar(32) NOT NULL COMMENT 'COMPENSATED / DEAD',
  `event_id` varchar(64) DEFAULT NULL,
  `order_id` bigint(20) UNSIGNED DEFAULT NULL,
  `user_id` bigint(20) UNSIGNED DEFAULT NULL,
  `slot_id` bigint(20) UNSIGNED DEFAULT NULL,
  `service_point_id` bigint(20) UNSIGNED DEFAULT NULL,
  `reason` varchar(512) DEFAULT NULL,
  `payload` text,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_type_event` (`failure_type`, `event_id`),
  KEY `idx_type_status_time` (`failure_type`, `status`, `create_time`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_slot_user` (`slot_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `appointment_slot` (`id`, `service_point_id`, `title`, `description`, `total_quota`, `available_quota`, `start_time`, `end_time`, `status`) VALUES
(1, 4, '宿舍维修上午上门', '水电、门窗维修预约。', 30, 30, '2026-06-20 09:00:00', '2026-06-20 12:00:00', 1),
(2, 5, '就业咨询下午场', '简历修改和职业规划咨询。', 20, 20, '2026-06-20 14:00:00', '2026-06-20 17:00:00', 1),
(3, 3, '图书馆打印高峰时段', '考试周打印和装订预约。', 50, 50, '2026-06-21 10:00:00', '2026-06-21 12:00:00', 1);

-- Redis initialization for appointment reservation:
-- SET appointment:quota:1 30
-- SET appointment:quota:2 20
-- SET appointment:quota:3 50
-- XGROUP CREATE stream.appointment-orders g1 0 MKSTREAM

DROP TABLE IF EXISTS `service_ticket`;
CREATE TABLE `service_ticket` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `service_point_id` bigint(20) UNSIGNED DEFAULT NULL,
  `category_id` bigint(20) UNSIGNED DEFAULT NULL,
  `contact_phone` varchar(32) DEFAULT NULL,
  `detail_address` varchar(255) DEFAULT NULL,
  `attachment_name` varchar(255) DEFAULT NULL,
  `attachment_url` varchar(512) DEFAULT NULL,
  `attachment_size` bigint(20) UNSIGNED DEFAULT NULL,
  `attachment_type` varchar(128) DEFAULT NULL,
  `user_hidden` tinyint(1) DEFAULT 0,
  `admin_deleted` tinyint(1) DEFAULT 0,
  `delete_remark` varchar(512) DEFAULT NULL,
  `deleted_by` bigint(20) UNSIGNED DEFAULT NULL,
  `student_reply_required` tinyint(1) DEFAULT 0,
  `title` varchar(128) NOT NULL,
  `content` varchar(2048) NOT NULL,
  `priority` tinyint(1) DEFAULT 1,
  `status` tinyint(1) DEFAULT 0,
  `assignee_id` bigint(20) UNSIGNED DEFAULT NULL,
  `ai_summary` varchar(512) DEFAULT NULL,
  `ai_category` varchar(64) DEFAULT NULL,
  `rating` tinyint(1) DEFAULT NULL,
  `evaluation` varchar(512) DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `accept_time` datetime DEFAULT NULL,
  `finish_time` datetime DEFAULT NULL,
  `evaluate_time` datetime DEFAULT NULL,
  `delete_time` datetime DEFAULT NULL,
  `student_reply_time` datetime DEFAULT NULL,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_service_point_id` (`service_point_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS `ticket_comment`;
CREATE TABLE `ticket_comment` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `ticket_id` bigint(20) UNSIGNED NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `user_type` tinyint(1) DEFAULT 0,
  `content` varchar(1024) NOT NULL,
  `attachment_name` varchar(255) DEFAULT NULL,
  `attachment_url` varchar(512) DEFAULT NULL,
  `attachment_size` bigint(20) UNSIGNED DEFAULT NULL,
  `attachment_type` varchar(128) DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ticket_id` (`ticket_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `service_ticket` (`id`, `user_id`, `service_point_id`, `category_id`, `title`, `content`, `priority`, `status`, `ai_summary`, `ai_category`) VALUES
(1, 1, 4, 4, '宿舍水龙头漏水', '宿舍 302 水龙头一直漏水，需要维修。', 2, 0, '宿舍水龙头漏水维修请求。', '维修'),
(2, 1, 3, 3, '打印机无法读取校园卡', '图书馆打印机在支付时无法读取我的校园卡。', 1, 1, '打印支付时校园卡读取问题。', '打印');

INSERT INTO `ticket_comment` (`id`, `ticket_id`, `user_id`, `user_type`, `content`) VALUES
(1, 2, 1, 0, '我换了一台打印机尝试，仍然出现同样的问题。');

DROP TABLE IF EXISTS `ai_knowledge`;
CREATE TABLE `ai_knowledge` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `title` varchar(128) NOT NULL,
  `content` varchar(2048) NOT NULL,
  `category` varchar(64) DEFAULT NULL,
  `source` varchar(128) DEFAULT NULL,
  `status` tinyint(1) DEFAULT 1,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_category` (`category`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO `ai_knowledge` (`id`, `title`, `content`, `category`, `source`, `status`) VALUES
(1, '校园卡遗失', '校园卡遗失后，请先到校园卡服务中心挂失，并携带学生证办理补卡。', '校园卡', '校园常见问题', 1),
(2, '宿舍维修', '宿舍维修请创建维修工单，写清楼栋、房间号、问题描述和可上门时间。', '维修', '校园常见问题', 1),
(3, '打印服务', '图书馆打印点支持打印、复印和装订；如支付失败，请联系打印点工作人员。', '打印', '校园常见问题', 1),
(4, '快递取件', '校园快递站处理包裹取件、取件码异常和包裹丢失追踪。', '快递', '校园常见问题', 1),
(5, '就业咨询', '就业咨询室支持简历修改、面试准备和就业政策咨询。', '咨询', '校园常见问题', 1);

DROP TABLE IF EXISTS `ai_session`;
CREATE TABLE `ai_session` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) UNSIGNED DEFAULT NULL,
  `title` varchar(128) NOT NULL,
  `scene` varchar(64) DEFAULT NULL,
  `pinned` tinyint(1) DEFAULT 0,
  `status` tinyint(1) DEFAULT 1,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_scene` (`scene`),
  KEY `idx_user_scene_pinned_time` (`user_id`, `scene`, `status`, `pinned`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS `ai_message`;
CREATE TABLE `ai_message` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `session_id` bigint(20) UNSIGNED NOT NULL,
  `turn_id` varchar(64) DEFAULT NULL,
  `user_id` bigint(20) UNSIGNED DEFAULT NULL,
  `role` varchar(32) NOT NULL,
  `content` text NOT NULL,
  `intent` varchar(64) DEFAULT NULL,
  `metadata` text DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_session_message_id` (`session_id`, `id`),
  UNIQUE KEY `uk_session_turn_role` (`session_id`, `turn_id`, `role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS `ai_session_memory`;
CREATE TABLE `ai_session_memory` (
  `session_id` bigint(20) UNSIGNED NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `schema_version` varchar(16) NOT NULL DEFAULT '2',
  `last_processed_message_id` bigint(20) UNSIGNED NOT NULL DEFAULT 0,
  `last_model_summary_message_id` bigint(20) UNSIGNED NOT NULL DEFAULT 0,
  `rolling_summary` text DEFAULT NULL,
  `entities_json` json DEFAULT NULL,
  `summary_source` enum('deterministic','model') NOT NULL DEFAULT 'deterministic',
  `summary_status` enum('ready','pending','degraded','rebuild_required') NOT NULL DEFAULT 'ready',
  `version` bigint(20) UNSIGNED NOT NULL DEFAULT 0,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`session_id`),
  KEY `idx_memory_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS `operation_log`;
CREATE TABLE `operation_log` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) UNSIGNED DEFAULT NULL,
  `user_role` varchar(32) DEFAULT NULL,
  `module` varchar(64) NOT NULL,
  `operation` varchar(128) NOT NULL,
  `request_method` varchar(16) DEFAULT NULL,
  `request_uri` varchar(255) DEFAULT NULL,
  `class_method` varchar(255) DEFAULT NULL,
  `params` varchar(2048) DEFAULT NULL,
  `business_type` varchar(64) DEFAULT NULL,
  `business_id` bigint(20) UNSIGNED DEFAULT NULL,
  `before_status` varchar(32) DEFAULT NULL,
  `after_status` varchar(32) DEFAULT NULL,
  `remark_summary` varchar(512) DEFAULT NULL,
  `success` tinyint(1) DEFAULT 1,
  `error_msg` varchar(512) DEFAULT NULL,
  `cost_time` bigint(20) DEFAULT NULL,
  `ip` varchar(64) DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_module` (`module`),
  KEY `idx_business` (`business_type`, `business_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TABLE IF EXISTS `station_comment_cleanup_task`;
CREATE TABLE `station_comment_cleanup_task` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `message_id` varchar(64) NOT NULL,
  `station_id` bigint(20) UNSIGNED NOT NULL,
  `root_comment_id` bigint(20) UNSIGNED NOT NULL,
  `deleted_by` bigint(20) UNSIGNED NOT NULL,
  `status` tinyint(1) NOT NULL DEFAULT 0 COMMENT '0=pending,1=done,-1=failed',
  `retry_count` int(11) NOT NULL DEFAULT 0,
  `error_msg` varchar(512) DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_message_id` (`message_id`),
  KEY `idx_status_retry_id` (`status`, `retry_count`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MQ unavailable fallback tasks for station comment cascade cleanup';

DROP TABLE IF EXISTS `admin_comment_view`;
CREATE TABLE `admin_comment_view` (
  `comment_id` bigint(20) UNSIGNED NOT NULL COMMENT 'same as station_comment.id',
  `station_id` bigint(20) UNSIGNED NOT NULL,
  `parent_id` bigint(20) UNSIGNED NOT NULL DEFAULT 0,
  `root_id` bigint(20) UNSIGNED NOT NULL DEFAULT 0,
  `floor_no` int(11) DEFAULT NULL,
  `admin_id` bigint(20) UNSIGNED NOT NULL,
  `admin_type` varchar(32) NOT NULL,
  `content` varchar(1024) NOT NULL,
  `reply_to_comment_id` bigint(20) UNSIGNED DEFAULT NULL,
  `reply_to_user_id` bigint(20) UNSIGNED DEFAULT NULL,
  `reply_to_user_name` varchar(128) DEFAULT NULL,
  `reply_to_content` varchar(1024) DEFAULT NULL,
  `status` tinyint(1) NOT NULL DEFAULT 1 COMMENT '1=normal,-1=deleted',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`comment_id`),
  KEY `idx_station_status_comment` (`station_id`, `status`, `comment_id` DESC),
  KEY `idx_station_root_status_comment` (`station_id`, `root_id`, `status`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Shadow table for high-frequency admin-only comment queries without joins';

DROP TABLE IF EXISTS `station_comment`;
CREATE TABLE `station_comment` (
  `id` bigint(20) UNSIGNED NOT NULL COMMENT 'snowflake id for clustered-index cursor pagination',
  `station_id` bigint(20) UNSIGNED NOT NULL COMMENT 'service_point.id, one station owns one board',
  `parent_id` bigint(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 means floor comment',
  `root_id` bigint(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT 'floor id for nested replies',
  `reply_to_comment_id` bigint(20) UNSIGNED DEFAULT NULL,
  `reply_to_user_id` bigint(20) UNSIGNED DEFAULT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `user_type` varchar(32) NOT NULL DEFAULT 'student',
  `floor_no` int(11) DEFAULT NULL COMMENT 'monotonic floor number allocated by Redis INCR',
  `content` varchar(1024) NOT NULL,
  `like_count` int(11) NOT NULL DEFAULT 0,
  `reply_count` int(11) NOT NULL DEFAULT 0,
  `status` tinyint(1) NOT NULL DEFAULT 1 COMMENT '1=normal,-1=soft deleted',
  `deleted_by` bigint(20) UNSIGNED DEFAULT NULL,
  `delete_time` datetime DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_station_floor` (`station_id`, `floor_no`),
  KEY `idx_station_parent_status_id` (`station_id`, `parent_id`, `status`, `id`),
  KEY `idx_station_root_status_id` (`station_id`, `root_id`, `status`, `id`),
  KEY `idx_station_status_id_desc` (`station_id`, `status`, `id` DESC),
  KEY `idx_station_status_id_asc` (`station_id`, `status`, `id` ASC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Tieba-style two-level comments under a service point';

SET FOREIGN_KEY_CHECKS = 1;
