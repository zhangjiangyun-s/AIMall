-- RAG stage 22: retry scheduling and worker heartbeat.
USE aimall;
SET NAMES utf8mb4;

SET @schema_name = DATABASE();

SET @sql = IF(
  EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'knowledge_index_task'
      AND column_name = 'next_retry_at'
  ),
  'SELECT 1',
  'ALTER TABLE knowledge_index_task ADD COLUMN next_retry_at datetime NULL AFTER timeout_at'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'knowledge_index_task'
      AND column_name = 'last_heartbeat_at'
  ),
  'SELECT 1',
  'ALTER TABLE knowledge_index_task ADD COLUMN last_heartbeat_at datetime NULL AFTER next_retry_at'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  EXISTS(
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'knowledge_index_task'
      AND index_name = 'idx_knowledge_task_retry'
  ),
  'SELECT 1',
  'ALTER TABLE knowledge_index_task ADD INDEX idx_knowledge_task_retry (status, next_retry_at)'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
