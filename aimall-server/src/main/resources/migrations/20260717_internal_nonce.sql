CREATE TABLE `internal_request_nonce` (
  `nonce` varchar(64) NOT NULL,
  `expires_at` datetime NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`nonce`),
  KEY `idx_internal_nonce_expire` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
