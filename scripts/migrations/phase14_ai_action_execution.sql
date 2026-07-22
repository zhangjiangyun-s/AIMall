CREATE TABLE IF NOT EXISTS `ai_action_execution` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `action_id` varchar(64) NOT NULL,
  `action_type` varchar(32) NOT NULL,
  `member_id` bigint NOT NULL,
  `request_hash` char(64) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PROCESSING',
  `result_json` longtext DEFAULT NULL,
  `error_message` varchar(1000) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `finished_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_action_execution_action_id` (`action_id`),
  KEY `idx_ai_action_execution_member` (`member_id`, `created_at`),
  KEY `idx_ai_action_execution_status` (`status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
