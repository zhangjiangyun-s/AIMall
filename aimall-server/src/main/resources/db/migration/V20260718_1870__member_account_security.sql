-- Resume safely when only part of the account-security DDL was previously applied.
DELIMITER $$
DROP PROCEDURE IF EXISTS aimall_1870_add_column$$
CREATE PROCEDURE aimall_1870_add_column(
    IN target_table VARCHAR(64),
    IN target_column VARCHAR(64),
    IN definition_sql TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = target_table
          AND column_name = target_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', target_table, '` ADD COLUMN `', target_column, '` ', definition_sql);
        PREPARE ddl_statement FROM @ddl;
        EXECUTE ddl_statement;
        DEALLOCATE PREPARE ddl_statement;
    END IF;
END$$
DELIMITER ;

CALL aimall_1870_add_column('ums_member', 'privacy_consent_version', 'varchar(32) DEFAULT NULL');
CALL aimall_1870_add_column('ums_member', 'privacy_consent_time', 'datetime DEFAULT NULL');
CALL aimall_1870_add_column('ums_member', 'password_changed_at', 'datetime DEFAULT NULL');
CALL aimall_1870_add_column('ums_member', 'cancelled_at', 'datetime DEFAULT NULL');

CREATE TABLE IF NOT EXISTS ums_member_login_history (
    id bigint NOT NULL AUTO_INCREMENT,
    member_id bigint DEFAULT NULL,
    username varchar(64) NOT NULL,
    client_ip varchar(64) NOT NULL,
    user_agent varchar(500) DEFAULT NULL,
    device_hash varchar(64) NOT NULL,
    success tinyint NOT NULL,
    risk_flag tinyint NOT NULL DEFAULT 0,
    failure_reason varchar(255) DEFAULT NULL,
    create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_member_login_history (member_id, create_time),
    KEY idx_member_login_risk (risk_flag, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ums_member_device (
    id bigint NOT NULL AUTO_INCREMENT,
    member_id bigint NOT NULL,
    device_hash varchar(64) NOT NULL,
    device_name varchar(100) NOT NULL,
    last_ip varchar(64) NOT NULL,
    trusted tinyint NOT NULL DEFAULT 0,
    revoked tinyint NOT NULL DEFAULT 0,
    first_seen_time datetime NOT NULL,
    last_seen_time datetime NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_device (member_id, device_hash),
    KEY idx_member_device_active (member_id, revoked, last_seen_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP PROCEDURE aimall_1870_add_column;
