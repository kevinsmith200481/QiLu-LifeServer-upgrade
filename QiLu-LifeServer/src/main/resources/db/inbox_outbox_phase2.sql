SET NAMES utf8mb4;

-- Phase 2 one-time incremental migration.
-- Run this script during a maintenance window before deploying the new code.
-- The migration deliberately fails when the expected legacy columns are absent;
-- do not silently skip an unknown schema version.
ALTER TABLE `inbox_delivery_task`
  CHANGE COLUMN `status` `delivery_status` varchar(16) NOT NULL DEFAULT 'WAITING'
    COMMENT 'WAITING/SUCCESS/RETRY_WAIT/DEAD',
  CHANGE COLUMN `retry_count` `delivery_attempts` int(11) NOT NULL DEFAULT 0,
  CHANGE COLUMN `error_msg` `last_delivery_error` varchar(512) DEFAULT NULL,
  ADD COLUMN `publish_status` varchar(16) NOT NULL DEFAULT 'PENDING'
    COMMENT 'PENDING/PUBLISHING/PUBLISHED/RETRY_WAIT/DEAD' AFTER `target_value`,
  ADD COLUMN `publish_attempts` int(11) NOT NULL DEFAULT 0 AFTER `publish_status`,
  ADD COLUMN `next_publish_time` datetime DEFAULT NULL AFTER `publish_attempts`,
  ADD COLUMN `lease_owner` varchar(128) DEFAULT NULL AFTER `next_publish_time`,
  ADD COLUMN `lease_until` datetime DEFAULT NULL AFTER `lease_owner`,
  ADD COLUMN `last_publish_time` datetime DEFAULT NULL AFTER `lease_until`,
  ADD COLUMN `last_publish_error` varchar(512) DEFAULT NULL AFTER `last_publish_time`,
  ADD COLUMN `next_delivery_time` datetime DEFAULT NULL AFTER `delivery_attempts`,
  ADD COLUMN `version` int(11) NOT NULL DEFAULT 0 AFTER `last_delivery_error`;

-- Preserve the legacy task meaning while splitting publication from delivery.
UPDATE `inbox_delivery_task`
SET `delivery_status` = CASE `delivery_status`
    WHEN '0' THEN 'WAITING'
    WHEN '1' THEN 'SUCCESS'
    WHEN '2' THEN 'RETRY_WAIT'
    WHEN '3' THEN 'DEAD'
    ELSE `delivery_status`
  END;

UPDATE `inbox_delivery_task`
SET `publish_status` = CASE
    WHEN `delivery_status` = 'SUCCESS' THEN 'PUBLISHED'
    WHEN `delivery_status` = 'DEAD' THEN 'DEAD'
    ELSE 'PENDING'
  END;

ALTER TABLE `inbox_delivery_task`
  DROP INDEX `idx_status_retry_id`,
  ADD KEY `idx_publish_ready` (`publish_status`, `next_publish_time`, `lease_until`, `id`),
  ADD KEY `idx_delivery_status` (`delivery_status`, `next_delivery_time`, `id`);

-- A task may produce at most one durable dead-letter record even after replay.
ALTER TABLE `inbox_dead_letter`
  DROP INDEX `idx_task_no`,
  ADD UNIQUE KEY `uk_task_no` (`task_no`);

-- The application initializer creates current and next month at startup. For
-- controlled DBA rollout, copy the two monthly table templates from
-- inbox_message.sql and replace 202607 with the deployment month, and then with
-- the following month, before starting the application.
