-- Phase 1 incremental migration for existing databases.
-- The migration deliberately aborts before ALTER when duplicate active orders exist.

DELIMITER $$

DROP PROCEDURE IF EXISTS `migrate_appointment_consistency_phase1`$$
CREATE PROCEDURE `migrate_appointment_consistency_phase1`()
BEGIN
  DECLARE duplicate_active_orders bigint DEFAULT 0;
  DECLARE active_slot_column_count int DEFAULT 0;
  DECLARE active_slot_index_count int DEFAULT 0;

  SELECT COUNT(*) INTO duplicate_active_orders
  FROM (
    SELECT `user_id`, `slot_id`
    FROM `appointment_order`
    WHERE `status` = 1
    GROUP BY `user_id`, `slot_id`
    HAVING COUNT(*) > 1
  ) conflicts;

  IF duplicate_active_orders > 0 THEN
    SELECT `user_id`, `slot_id`, COUNT(*) AS `active_order_count`,
           GROUP_CONCAT(`id` ORDER BY `id`) AS `conflicting_order_ids`
    FROM `appointment_order`
    WHERE `status` = 1
    GROUP BY `user_id`, `slot_id`
    HAVING COUNT(*) > 1;
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'appointment phase1 migration aborted: duplicate active orders exist';
  END IF;

  SELECT COUNT(*) INTO active_slot_column_count
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'appointment_order'
    AND COLUMN_NAME = 'active_slot_id';

  IF active_slot_column_count = 0 THEN
    ALTER TABLE `appointment_order`
      ADD COLUMN `active_slot_id` bigint(20) UNSIGNED
      GENERATED ALWAYS AS (CASE WHEN `status` = 1 THEN `slot_id` ELSE NULL END) STORED
      AFTER `status`;
  END IF;

  SELECT COUNT(*) INTO active_slot_index_count
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'appointment_order'
    AND INDEX_NAME = 'uk_user_active_slot';

  IF active_slot_index_count = 0 THEN
    ALTER TABLE `appointment_order`
      ADD UNIQUE KEY `uk_user_active_slot` (`user_id`, `active_slot_id`);
  END IF;

  CREATE TABLE IF NOT EXISTS `appointment_consistency_repair` (
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
END$$

CALL `migrate_appointment_consistency_phase1`()$$
DROP PROCEDURE `migrate_appointment_consistency_phase1`$$

DELIMITER ;
