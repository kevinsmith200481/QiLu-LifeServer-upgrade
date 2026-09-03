-- AI Memory 阶段 E：记录模型滚动摘要覆盖到的消息位置。
-- 采用 information_schema 守卫，确保已有数据库可重复执行迁移。

DELIMITER $$

DROP PROCEDURE IF EXISTS `migrate_ai_memory_stage_e`$$
CREATE PROCEDURE `migrate_ai_memory_stage_e`()
BEGIN
  DECLARE model_position_column_count int DEFAULT 0;

  SELECT COUNT(*) INTO model_position_column_count
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ai_session_memory'
    AND COLUMN_NAME = 'last_model_summary_message_id';

  IF model_position_column_count = 0 THEN
    ALTER TABLE `ai_session_memory`
      ADD COLUMN `last_model_summary_message_id` bigint(20) UNSIGNED NOT NULL DEFAULT 0
      AFTER `last_processed_message_id`;
  END IF;
END$$

CALL `migrate_ai_memory_stage_e`()$$
DROP PROCEDURE `migrate_ai_memory_stage_e`$$

DELIMITER ;
