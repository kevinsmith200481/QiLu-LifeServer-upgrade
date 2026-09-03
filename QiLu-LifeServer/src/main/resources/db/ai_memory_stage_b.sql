-- AI Memory 阶段 B：为已有数据库增加完整轮次标识和会话 Memory 表。
-- 本脚本可重复执行；若已有非空 turn_id 数据违反唯一约束，则在创建索引前明确中止。

DELIMITER $$

DROP PROCEDURE IF EXISTS `migrate_ai_memory_stage_b`$$
CREATE PROCEDURE `migrate_ai_memory_stage_b`()
BEGIN
  DECLARE turn_column_count int DEFAULT 0;
  DECLARE session_id_index_count int DEFAULT 0;
  DECLARE session_message_index_count int DEFAULT 0;
  DECLARE turn_role_index_count int DEFAULT 0;
  DECLARE duplicate_turn_roles bigint DEFAULT 0;

  SELECT COUNT(*) INTO turn_column_count
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ai_message'
    AND COLUMN_NAME = 'turn_id';

  IF turn_column_count = 0 THEN
    ALTER TABLE `ai_message`
      ADD COLUMN `turn_id` varchar(64) DEFAULT NULL AFTER `session_id`;
  END IF;

  SELECT COUNT(*) INTO duplicate_turn_roles
  FROM (
    SELECT `session_id`, `turn_id`, `role`
    FROM `ai_message`
    WHERE `turn_id` IS NOT NULL
    GROUP BY `session_id`, `turn_id`, `role`
    HAVING COUNT(*) > 1
  ) AS conflicts;

  IF duplicate_turn_roles > 0 THEN
    SELECT `session_id`, `turn_id`, `role`, COUNT(*) AS `duplicate_count`
    FROM `ai_message`
    WHERE `turn_id` IS NOT NULL
    GROUP BY `session_id`, `turn_id`, `role`
    HAVING COUNT(*) > 1;
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'AI Memory stage B migration aborted: duplicate turn roles exist';
  END IF;

  SELECT COUNT(*) INTO session_message_index_count
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ai_message'
    AND INDEX_NAME = 'idx_session_message_id';

  IF session_message_index_count = 0 THEN
    ALTER TABLE `ai_message`
      ADD KEY `idx_session_message_id` (`session_id`, `id`);
  END IF;

  SELECT COUNT(*) INTO turn_role_index_count
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ai_message'
    AND INDEX_NAME = 'uk_session_turn_role';

  IF turn_role_index_count = 0 THEN
    ALTER TABLE `ai_message`
      ADD UNIQUE KEY `uk_session_turn_role` (`session_id`, `turn_id`, `role`);
  END IF;

  -- 复合索引已覆盖 session_id 左前缀，移除旧单列索引，避免重复维护。
  SELECT COUNT(*) INTO session_id_index_count
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ai_message'
    AND INDEX_NAME = 'idx_session_id';

  IF session_id_index_count > 0 THEN
    ALTER TABLE `ai_message` DROP INDEX `idx_session_id`;
  END IF;

  CREATE TABLE IF NOT EXISTS `ai_session_memory` (
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
END$$

CALL `migrate_ai_memory_stage_b`()$$
DROP PROCEDURE `migrate_ai_memory_stage_b`$$

DELIMITER ;
