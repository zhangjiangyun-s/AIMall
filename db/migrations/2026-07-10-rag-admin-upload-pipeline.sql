USE aimall;

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `knowledge_doc_version` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_id` bigint NOT NULL,
  `version_no` int NOT NULL,
  `file_name` varchar(255) NOT NULL,
  `file_type` varchar(32) NOT NULL,
  `file_size` bigint NOT NULL DEFAULT 0,
  `source_hash` varchar(64) NOT NULL,
  `storage_path` varchar(500) NOT NULL,
  `parsed_json_path` varchar(500) DEFAULT NULL,
  `preview_text_path` varchar(500) DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'DRAFT',
  `page_count` int NOT NULL DEFAULT 0,
  `paragraph_count` int NOT NULL DEFAULT 0,
  `table_count` int NOT NULL DEFAULT 0,
  `image_count` int NOT NULL DEFAULT 0,
  `pii_count` int NOT NULL DEFAULT 0,
  `prompt_risk_level` varchar(32) NOT NULL DEFAULT 'LOW',
  `quality_score` decimal(5,2) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_doc_version` (`doc_id`, `version_no`),
  KEY `idx_knowledge_doc_version_status` (`status`),
  KEY `idx_knowledge_doc_version_hash` (`source_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `knowledge_task_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` varchar(64) NOT NULL,
  `event_type` varchar(64) NOT NULL,
  `title` varchar(200) NOT NULL,
  `detail` text,
  `progress_current` int DEFAULT NULL,
  `progress_total` int DEFAULT NULL,
  `ok` tinyint(1) NOT NULL DEFAULT 1,
  `error_code` varchar(64) DEFAULT NULL,
  `error_stack` mediumtext,
  `suggestion` varchar(1000) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_knowledge_task_event_task` (`task_id`, `id`),
  KEY `idx_knowledge_task_event_type` (`event_type`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `knowledge_retrieval_test` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_id` bigint NOT NULL,
  `doc_version_id` bigint DEFAULT NULL,
  `test_query` varchar(500) NOT NULL,
  `expected_doc_id` bigint DEFAULT NULL,
  `hit_doc_id` bigint DEFAULT NULL,
  `hit_chunk_id` bigint DEFAULT NULL,
  `top_score` decimal(10,4) DEFAULT NULL,
  `passed` tinyint(1) NOT NULL DEFAULT 0,
  `detail` text,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_knowledge_retrieval_test_doc` (`doc_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `knowledge_quality_report` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_id` bigint NOT NULL,
  `doc_version_id` bigint DEFAULT NULL,
  `parse_score` decimal(5,2) DEFAULT NULL,
  `chunk_score` decimal(5,2) DEFAULT NULL,
  `pii_score` decimal(5,2) DEFAULT NULL,
  `prompt_risk_score` decimal(5,2) DEFAULT NULL,
  `retrieval_score` decimal(5,2) DEFAULT NULL,
  `sync_score` decimal(5,2) DEFAULT NULL,
  `total_score` decimal(5,2) DEFAULT NULL,
  `grade` varchar(8) DEFAULT NULL,
  `detail` text,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_knowledge_quality_report_doc` (`doc_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `embedding_cache` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `content_hash` varchar(64) NOT NULL,
  `embedding_model` varchar(128) NOT NULL,
  `vector_dimension` int NOT NULL DEFAULT 0,
  `embedding_id` varchar(128) DEFAULT NULL,
  `hit_count` int NOT NULL DEFAULT 0,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `expired_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_embedding_cache_hash_model` (`content_hash`, `embedding_model`),
  KEY `idx_embedding_cache_expired` (`expired_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP PROCEDURE IF EXISTS add_column_if_missing;

DELIMITER //
CREATE PROCEDURE add_column_if_missing(IN table_name_in varchar(64), IN column_name_in varchar(64), IN ddl_in text)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = table_name_in
      AND column_name = column_name_in
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', table_name_in, '` ADD COLUMN ', ddl_in);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

CALL add_column_if_missing('knowledge_doc', 'tenant_id', '`tenant_id` varchar(64) NOT NULL DEFAULT ''default''');
CALL add_column_if_missing('knowledge_doc', 'current_version_id', '`current_version_id` bigint DEFAULT NULL');
CALL add_column_if_missing('knowledge_doc', 'owner_user_id', '`owner_user_id` bigint DEFAULT NULL');

CALL add_column_if_missing('knowledge_chunk', 'doc_version_id', '`doc_version_id` bigint DEFAULT NULL');
CALL add_column_if_missing('knowledge_chunk', 'chunk_type', '`chunk_type` varchar(32) NOT NULL DEFAULT ''TEXT''');
CALL add_column_if_missing('knowledge_chunk', 'chunk_hash', '`chunk_hash` varchar(64) DEFAULT NULL');
CALL add_column_if_missing('knowledge_chunk', 'page_start', '`page_start` int DEFAULT NULL');
CALL add_column_if_missing('knowledge_chunk', 'page_end', '`page_end` int DEFAULT NULL');

CALL add_column_if_missing('knowledge_index_task', 'task_id', '`task_id` varchar(64) DEFAULT NULL');
CALL add_column_if_missing('knowledge_index_task', 'doc_version_id', '`doc_version_id` bigint DEFAULT NULL');
CALL add_column_if_missing('knowledge_index_task', 'current_step', '`current_step` varchar(64) DEFAULT NULL');
CALL add_column_if_missing('knowledge_index_task', 'progress_current', '`progress_current` int NOT NULL DEFAULT 0');
CALL add_column_if_missing('knowledge_index_task', 'progress_total', '`progress_total` int NOT NULL DEFAULT 0');
CALL add_column_if_missing('knowledge_index_task', 'timeout_at', '`timeout_at` datetime DEFAULT NULL');
CALL add_column_if_missing('knowledge_index_task', 'dead_letter_reason', '`dead_letter_reason` varchar(1000) DEFAULT NULL');

UPDATE `knowledge_index_task`
SET `task_id` = CONCAT('KT', DATE_FORMAT(IFNULL(`created_at`, NOW()), '%Y%m%d'), LPAD(`id`, 8, '0'))
WHERE `task_id` IS NULL OR `task_id` = '';

DROP PROCEDURE add_column_if_missing;
