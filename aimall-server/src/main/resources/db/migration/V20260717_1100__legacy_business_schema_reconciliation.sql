-- Reconciles every pre-Flyway business migration in resources/migrations.
-- It is intentionally idempotent because MySQL DDL may have been applied only partly.
-- Sources: 20260716_cart_uniqueness.sql, 20260716_transaction_safety.sql,
-- 20260717_admin_operation_audit.sql, 20260717_admin_security.sql,
-- 20260717_coupon_model.sql, 20260717_internal_nonce.sql,
-- 20260717_knowledge_version_guard.sql, 20260717_logistics.sql,
-- 20260717_order_expiration.sql, 20260717_order_inventory_state.sql,
-- 20260717_product_catalog.sql, 20260717_refund_manual_recovery.sql,
-- 20260717_refund_task_state.sql, 20260717_remove_legacy_home_recommend.sql,
-- 20260717_return_items.sql, 20260717_return_uniqueness.sql.
DELIMITER $$
DROP PROCEDURE IF EXISTS aimall_add_column_if_missing$$
DROP PROCEDURE IF EXISTS aimall_add_index_if_missing$$
DROP PROCEDURE IF EXISTS aimall_drop_index_if_exists$$
CREATE PROCEDURE aimall_add_column_if_missing(IN target_table VARCHAR(64), IN target_column VARCHAR(64), IN definition_sql TEXT)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = target_table AND column_name = target_column) THEN
        SET @sql = CONCAT('ALTER TABLE `', target_table, '` ADD COLUMN `', target_column, '` ', definition_sql);
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END$$
CREATE PROCEDURE aimall_add_index_if_missing(IN target_table VARCHAR(64), IN target_index VARCHAR(64), IN definition_sql TEXT)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = target_table AND index_name = target_index) THEN
        SET @sql = CONCAT('ALTER TABLE `', target_table, '` ADD ', definition_sql);
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END$$
CREATE PROCEDURE aimall_drop_index_if_exists(IN target_table VARCHAR(64), IN target_index VARCHAR(64))
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = target_table AND index_name = target_index) THEN
        SET @sql = CONCAT('ALTER TABLE `', target_table, '` DROP INDEX `', target_index, '`');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

-- 20260716_transaction_safety.sql and 20260716_cart_uniqueness.sql
CALL aimall_add_column_if_missing('pms_product', 'lock_stock', 'int NOT NULL DEFAULT 0 AFTER `stock`');
CALL aimall_add_column_if_missing('oms_cart_item', 'sku_identity', 'bigint GENERATED ALWAYS AS (COALESCE(`product_sku_id`, 0)) STORED');
CALL aimall_add_column_if_missing('oms_cart_item', 'active_identity', 'tinyint GENERATED ALWAYS AS (CASE WHEN `delete_status` = 0 THEN 1 ELSE NULL END) STORED');
CREATE TEMPORARY TABLE aimall_cart_duplicate_keep AS SELECT MIN(id) AS keep_id, member_id, product_id, sku_identity, LEAST(99, SUM(quantity)) AS merged_quantity FROM oms_cart_item WHERE delete_status = 0 GROUP BY member_id, product_id, sku_identity HAVING COUNT(*) > 1;
UPDATE oms_cart_item item JOIN aimall_cart_duplicate_keep kept ON kept.keep_id = item.id SET item.quantity = kept.merged_quantity, item.modify_date = NOW();
UPDATE oms_cart_item item JOIN aimall_cart_duplicate_keep kept ON kept.member_id = item.member_id AND kept.product_id = item.product_id AND kept.sku_identity = item.sku_identity SET item.delete_status = 1, item.modify_date = NOW() WHERE item.id <> kept.keep_id AND item.delete_status = 0;
DROP TEMPORARY TABLE aimall_cart_duplicate_keep;
CALL aimall_add_index_if_missing('oms_cart_item', 'uk_cart_active_item', 'UNIQUE KEY `uk_cart_active_item` (`member_id`, `product_id`, `sku_identity`, `active_identity`)');
CALL aimall_add_column_if_missing('oms_order', 'refund_status', 'varchar(32) NOT NULL DEFAULT ''NONE'' AFTER `delete_status`');
CALL aimall_add_column_if_missing('oms_order', 'refunded_amount', 'decimal(10,2) NOT NULL DEFAULT 0.00 AFTER `refund_status`');

CREATE TABLE IF NOT EXISTS oms_order_request (id bigint NOT NULL AUTO_INCREMENT, member_id bigint NOT NULL, request_id varchar(64) NOT NULL, status varchar(32) NOT NULL DEFAULT 'PROCESSING', order_id bigint DEFAULT NULL, order_sn varchar(64) DEFAULT NULL, create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, PRIMARY KEY (id), UNIQUE KEY uk_oms_order_request_member (member_id, request_id), KEY idx_oms_order_request_order (order_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS oms_refund_record (id bigint NOT NULL AUTO_INCREMENT, request_id varchar(64) NOT NULL, return_apply_id bigint NOT NULL, order_id bigint NOT NULL, order_sn varchar(64) NOT NULL, refund_channel varchar(32) NOT NULL, refund_status varchar(32) NOT NULL DEFAULT 'PENDING', amount decimal(10,2) NOT NULL, refund_transaction_no varchar(64) DEFAULT NULL, failure_reason varchar(500) DEFAULT NULL, create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, PRIMARY KEY (id), UNIQUE KEY uk_refund_request_id (request_id), UNIQUE KEY uk_refund_return_apply (return_apply_id), KEY idx_refund_order_id (order_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 20260717_admin_operation_audit.sql, admin_security.sql and internal_nonce.sql
CREATE TABLE IF NOT EXISTS admin_operation_audit (id bigint NOT NULL AUTO_INCREMENT, operator_id bigint DEFAULT NULL, operator_name varchar(64) DEFAULT NULL, http_method varchar(10) NOT NULL, request_uri varchar(500) NOT NULL, client_ip varchar(64) NOT NULL, trace_id varchar(64) DEFAULT NULL, success tinyint NOT NULL, error_message varchar(500) DEFAULT NULL, duration_ms bigint NOT NULL DEFAULT 0, create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id), KEY idx_admin_audit_operator (operator_id, create_time), KEY idx_admin_audit_uri (request_uri, create_time)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CALL aimall_add_column_if_missing('ums_admin', 'mfa_secret', 'varchar(128) DEFAULT NULL AFTER `status`');
CALL aimall_add_column_if_missing('ums_admin', 'mfa_enabled', 'tinyint NOT NULL DEFAULT 0 AFTER `mfa_secret`');
CREATE TABLE IF NOT EXISTS ums_admin_role (id bigint NOT NULL AUTO_INCREMENT, role_code varchar(64) NOT NULL, role_name varchar(100) NOT NULL, status tinyint NOT NULL DEFAULT 1, create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id), UNIQUE KEY uk_admin_role_code (role_code)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ums_admin_permission (id bigint NOT NULL AUTO_INCREMENT, permission_code varchar(100) NOT NULL, permission_name varchar(100) NOT NULL, create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id), UNIQUE KEY uk_admin_permission_code (permission_code)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ums_admin_role_relation (admin_id bigint NOT NULL, role_id bigint NOT NULL, create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (admin_id, role_id), KEY idx_admin_role_role (role_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ums_role_permission_relation (role_id bigint NOT NULL, permission_id bigint NOT NULL, create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (role_id, permission_id), KEY idx_role_permission_permission (permission_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ums_admin_login_attempt (id bigint NOT NULL AUTO_INCREMENT, username varchar(64) NOT NULL, client_ip varchar(64) NOT NULL, success tinyint NOT NULL DEFAULT 0, create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id), KEY idx_admin_login_attempt_guard (username, client_ip, success, create_time)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS internal_request_nonce (nonce varchar(64) NOT NULL, expires_at datetime NOT NULL, create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (nonce), KEY idx_internal_nonce_expire (expires_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 20260717_coupon_model.sql
CALL aimall_add_column_if_missing('sms_coupon', 'total_quantity', 'int NOT NULL DEFAULT 0 AFTER `status`');
CALL aimall_add_column_if_missing('sms_coupon', 'remaining_quantity', 'int NOT NULL DEFAULT 0 AFTER `total_quantity`');
CALL aimall_add_column_if_missing('sms_coupon', 'per_member_limit', 'int NOT NULL DEFAULT 1 AFTER `remaining_quantity`');
CALL aimall_add_column_if_missing('sms_coupon', 'receive_start_time', 'datetime DEFAULT NULL AFTER `per_member_limit`');
CALL aimall_add_column_if_missing('sms_coupon', 'receive_end_time', 'datetime DEFAULT NULL AFTER `receive_start_time`');
CALL aimall_add_column_if_missing('sms_coupon', 'scope_type', 'varchar(20) NOT NULL DEFAULT ''ALL'' AFTER `receive_end_time`');
CALL aimall_add_column_if_missing('sms_coupon', 'scope_ids', 'varchar(1000) DEFAULT NULL AFTER `scope_type`');
CALL aimall_add_column_if_missing('sms_coupon', 'budget_amount', 'decimal(14,2) DEFAULT NULL AFTER `scope_ids`');
CALL aimall_add_column_if_missing('sms_coupon', 'used_budget', 'decimal(14,2) NOT NULL DEFAULT 0.00 AFTER `budget_amount`');
CALL aimall_add_column_if_missing('sms_coupon', 'refund_policy', 'varchar(20) NOT NULL DEFAULT ''RETURN'' AFTER `used_budget`');
CALL aimall_add_column_if_missing('ums_member_coupon', 'claim_no', 'int NOT NULL DEFAULT 1 AFTER `coupon_name`');
CALL aimall_drop_index_if_exists('ums_member_coupon', 'uk_member_coupon_member_coupon');
CALL aimall_add_index_if_missing('ums_member_coupon', 'uk_member_coupon_claim', 'UNIQUE KEY `uk_member_coupon_claim` (`member_id`, `coupon_id`, `claim_no`)');

-- 20260717_order_expiration.sql, order_inventory_state.sql and product_catalog.sql
CALL aimall_add_column_if_missing('oms_order', 'expire_time', 'datetime DEFAULT NULL AFTER `modify_time`');
CALL aimall_add_index_if_missing('oms_order', 'idx_oms_order_expiration', 'KEY `idx_oms_order_expiration` (`status`, `expire_time`)');
CALL aimall_add_column_if_missing('oms_order', 'inventory_reservation_status', 'varchar(20) NOT NULL DEFAULT ''NOT_RESERVED'' AFTER `expire_time`');
CALL aimall_add_index_if_missing('oms_order', 'idx_oms_order_inventory_status', 'KEY `idx_oms_order_inventory_status` (`inventory_reservation_status`)');
CALL aimall_add_column_if_missing('pms_sku_stock', 'status', 'tinyint NOT NULL DEFAULT 1 AFTER `sp_data`');
CREATE TABLE IF NOT EXISTS pms_product_image (id bigint NOT NULL AUTO_INCREMENT, product_id bigint NOT NULL, image_url varchar(1000) NOT NULL, sort int NOT NULL DEFAULT 0, is_primary tinyint NOT NULL DEFAULT 0, create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id), KEY idx_product_image_product (product_id, sort)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 20260717_return_items.sql, return_uniqueness.sql, refund_task_state.sql and refund_manual_recovery.sql
CALL aimall_add_column_if_missing('oms_order_item', 'return_reserved_quantity', 'int NOT NULL DEFAULT 0 AFTER `product_attr`');
CALL aimall_add_column_if_missing('oms_order_item', 'refunded_quantity', 'int NOT NULL DEFAULT 0 AFTER `return_reserved_quantity`');
CALL aimall_add_column_if_missing('oms_order_item', 'shipped_quantity', 'int NOT NULL DEFAULT 0 AFTER `refunded_quantity`');
CREATE TABLE IF NOT EXISTS oms_order_return_item (id bigint NOT NULL AUTO_INCREMENT, return_apply_id bigint NOT NULL, order_id bigint NOT NULL, order_item_id bigint NOT NULL, product_id bigint NOT NULL, product_sku_id bigint DEFAULT NULL, quantity int NOT NULL, refund_amount decimal(10,2) NOT NULL, create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id), UNIQUE KEY uk_return_apply_order_item (return_apply_id, order_item_id), KEY idx_return_item_order_item (order_item_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CALL aimall_add_column_if_missing('oms_payment_record', 'refunded_amount', 'decimal(10,2) NOT NULL DEFAULT 0.00 AFTER `amount`');
CALL aimall_add_column_if_missing('oms_order_return_apply', 'active_identity', 'tinyint GENERATED ALWAYS AS (CASE WHEN `status` IN (0, 1, 5) THEN 1 ELSE NULL END) STORED');
CREATE TEMPORARY TABLE aimall_return_duplicate_keep AS SELECT MIN(id) AS keep_id, order_id, type FROM oms_order_return_apply WHERE status IN (0, 1, 5) GROUP BY order_id, type HAVING COUNT(*) > 1;
UPDATE oms_order_return_apply apply_row JOIN aimall_return_duplicate_keep kept ON kept.order_id = apply_row.order_id AND kept.type = apply_row.type SET apply_row.status = 4, apply_row.handle_note = 'Migration closed duplicate active return application', apply_row.handle_time = NOW(), apply_row.update_time = NOW() WHERE apply_row.id <> kept.keep_id AND apply_row.status IN (0, 1, 5);
DROP TEMPORARY TABLE aimall_return_duplicate_keep;
CALL aimall_add_index_if_missing('oms_order_return_apply', 'uk_return_active_order_type', 'UNIQUE KEY `uk_return_active_order_type` (`order_id`, `type`, `active_identity`)');
CALL aimall_add_column_if_missing('oms_refund_record', 'handle_note', 'varchar(500) DEFAULT NULL AFTER `failure_reason`');
CALL aimall_add_column_if_missing('oms_refund_record', 'retry_count', 'int NOT NULL DEFAULT 0 AFTER `handle_note`');
CALL aimall_add_column_if_missing('oms_refund_record', 'max_retry', 'int NOT NULL DEFAULT 8 AFTER `retry_count`');
CALL aimall_add_column_if_missing('oms_refund_record', 'next_retry_time', 'datetime DEFAULT NULL AFTER `max_retry`');
CALL aimall_add_column_if_missing('oms_refund_record', 'channel_called_time', 'datetime DEFAULT NULL AFTER `next_retry_time`');
CALL aimall_add_column_if_missing('oms_refund_record', 'finished_time', 'datetime DEFAULT NULL AFTER `channel_called_time`');
CALL aimall_add_column_if_missing('oms_refund_record', 'manual_retry_count', 'int NOT NULL DEFAULT 0 AFTER `max_retry`');
CALL aimall_add_column_if_missing('oms_refund_record', 'closed_time', 'datetime DEFAULT NULL AFTER `finished_time`');
CALL aimall_add_column_if_missing('oms_refund_record', 'closed_reason', 'varchar(500) DEFAULT NULL AFTER `closed_time`');

-- 20260717_logistics.sql and knowledge_version_guard.sql
CREATE TABLE IF NOT EXISTS oms_shipment (id bigint NOT NULL AUTO_INCREMENT, shipment_sn varchar(64) NOT NULL, order_id bigint NOT NULL, order_sn varchar(64) NOT NULL, carrier_code varchar(32) NOT NULL, carrier_name varchar(64) NOT NULL, tracking_no varchar(100) NOT NULL, status varchar(32) NOT NULL DEFAULT 'SHIPPED', shipped_at datetime NOT NULL, delivered_at datetime DEFAULT NULL, create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, PRIMARY KEY (id), UNIQUE KEY uk_shipment_sn (shipment_sn), UNIQUE KEY uk_shipment_tracking (carrier_code, tracking_no), KEY idx_shipment_order (order_id, status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS oms_shipment_item (id bigint NOT NULL AUTO_INCREMENT, shipment_id bigint NOT NULL, order_item_id bigint NOT NULL, quantity int NOT NULL, PRIMARY KEY (id), UNIQUE KEY uk_shipment_order_item (shipment_id, order_item_id), KEY idx_shipment_item_order_item (order_item_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS oms_logistics_event (id bigint NOT NULL AUTO_INCREMENT, shipment_id bigint NOT NULL, event_code varchar(32) NOT NULL, event_time datetime NOT NULL, location varchar(255) DEFAULT NULL, description varchar(500) NOT NULL, source varchar(32) NOT NULL DEFAULT 'ADMIN', create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id), KEY idx_logistics_event_shipment (shipment_id, event_time)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
DROP TABLE IF EXISTS sms_home_recommend_product;

DROP PROCEDURE aimall_add_column_if_missing;
DROP PROCEDURE aimall_add_index_if_missing;
DROP PROCEDURE aimall_drop_index_if_exists;
