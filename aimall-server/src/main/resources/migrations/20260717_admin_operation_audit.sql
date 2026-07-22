CREATE TABLE `admin_operation_audit` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `operator_id` bigint DEFAULT NULL,
  `operator_name` varchar(64) DEFAULT NULL,
  `http_method` varchar(10) NOT NULL,
  `request_uri` varchar(500) NOT NULL,
  `client_ip` varchar(64) NOT NULL,
  `trace_id` varchar(64) DEFAULT NULL,
  `success` tinyint NOT NULL,
  `error_message` varchar(500) DEFAULT NULL,
  `duration_ms` bigint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_admin_audit_operator` (`operator_id`, `create_time`),
  KEY `idx_admin_audit_uri` (`request_uri`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
