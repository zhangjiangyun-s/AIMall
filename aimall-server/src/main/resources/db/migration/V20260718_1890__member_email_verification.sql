SET @has_member_email = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ums_member' AND column_name = 'email'
);
SET @sql = IF(@has_member_email = 0,
    'ALTER TABLE ums_member ADD COLUMN email VARCHAR(254) DEFAULT NULL AFTER phone', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_member_email_unique = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ums_member' AND index_name = 'uk_ums_member_email'
);
SET @sql = IF(@has_member_email_unique = 0,
    'ALTER TABLE ums_member ADD UNIQUE KEY uk_ums_member_email (email)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS ums_email_verification_code (
  id bigint NOT NULL AUTO_INCREMENT,
  email varchar(254) NOT NULL,
  purpose varchar(32) NOT NULL,
  code_hash char(64) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE',
  failed_attempts int NOT NULL DEFAULT 0,
  max_attempts int NOT NULL DEFAULT 5,
  expires_at datetime NOT NULL,
  last_sent_at datetime NOT NULL,
  consumed_at datetime DEFAULT NULL,
  created_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  active_key varchar(300) GENERATED ALWAYS AS (
    CASE WHEN status = 'ACTIVE' THEN CONCAT(purpose, ':', email) ELSE NULL END
  ) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_email_verification_active (active_key),
  KEY idx_email_verification_expiry (status, expires_at),
  KEY idx_email_verification_email (email, purpose, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ums_email_verification_send_log (
  id bigint NOT NULL AUTO_INCREMENT,
  email varchar(254) NOT NULL,
  purpose varchar(32) NOT NULL,
  client_ip varchar(64) NOT NULL,
  success tinyint NOT NULL,
  failure_reason varchar(255) DEFAULT NULL,
  created_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_email_send_ip (client_ip, created_at),
  KEY idx_email_send_target (email, purpose, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
