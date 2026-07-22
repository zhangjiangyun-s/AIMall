-- Every DDL operation is independently guarded because MySQL autocommits DDL.
DELIMITER $$
DROP PROCEDURE IF EXISTS aimall_1860_add_column$$
DROP PROCEDURE IF EXISTS aimall_1860_add_index$$
CREATE PROCEDURE aimall_1860_add_column(
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
CREATE PROCEDURE aimall_1860_add_index(
    IN target_table VARCHAR(64),
    IN target_index VARCHAR(64),
    IN definition_sql TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = target_table
          AND index_name = target_index
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', target_table, '` ADD ', definition_sql);
        PREPARE ddl_statement FROM @ddl;
        EXECUTE ddl_statement;
        DEALLOCATE PREPARE ddl_statement;
    END IF;
END$$
DELIMITER ;

CALL aimall_1860_add_column('oms_order_return_apply', 'return_carrier', 'varchar(64) DEFAULT NULL');
CALL aimall_1860_add_column('oms_order_return_apply', 'return_tracking_no', 'varchar(100) DEFAULT NULL');
CALL aimall_1860_add_column('oms_order_return_apply', 'inspection_result', 'varchar(32) DEFAULT NULL');
CALL aimall_1860_add_column('oms_order_return_apply', 'inspection_note', 'varchar(500) DEFAULT NULL');
CALL aimall_1860_add_column('oms_order_return_apply', 'sla_deadline', 'datetime DEFAULT NULL');
CALL aimall_1860_add_column('oms_order_return_apply', 'sla_overdue', 'tinyint NOT NULL DEFAULT 0');
CALL aimall_1860_add_column('oms_order_return_apply', 'received_time', 'datetime DEFAULT NULL');

ALTER TABLE oms_order_return_apply
    MODIFY COLUMN active_identity tinyint
    GENERATED ALWAYS AS (CASE WHEN status IN (0,1,5,7,8) THEN 1 ELSE NULL END) STORED;

CALL aimall_1860_add_index(
    'oms_order_return_apply',
    'idx_return_sla',
    'KEY `idx_return_sla` (`status`, `sla_overdue`, `sla_deadline`)'
);

CREATE TABLE IF NOT EXISTS oms_return_evidence (
    id bigint NOT NULL AUTO_INCREMENT,
    return_apply_id bigint NOT NULL,
    member_id bigint NOT NULL,
    media_type varchar(16) NOT NULL,
    media_url varchar(1000) NOT NULL,
    create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_return_evidence_apply (return_apply_id, create_time),
    CONSTRAINT chk_return_media_type CHECK (media_type IN ('IMAGE', 'VIDEO'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS oms_return_status_event (
    id bigint NOT NULL AUTO_INCREMENT,
    return_apply_id bigint NOT NULL,
    from_status tinyint DEFAULT NULL,
    to_status tinyint DEFAULT NULL,
    event_type varchar(32) NOT NULL,
    operator_id bigint DEFAULT NULL,
    operator_type varchar(16) NOT NULL,
    note varchar(500) DEFAULT NULL,
    create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_return_event_apply (return_apply_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP PROCEDURE aimall_1860_add_column;
DROP PROCEDURE aimall_1860_add_index;
