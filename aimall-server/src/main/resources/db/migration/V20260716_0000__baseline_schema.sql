-- Flyway baseline for a completely empty database.
-- The target database is selected by the JDBC URL; migrations must never switch schemas.

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS `internal_request_nonce`;
DROP TABLE IF EXISTS `ums_role_permission_relation`;
DROP TABLE IF EXISTS `ums_admin_role_relation`;
DROP TABLE IF EXISTS `ums_admin_login_attempt`;
DROP TABLE IF EXISTS `admin_operation_audit`;
DROP TABLE IF EXISTS `ums_admin_permission`;
DROP TABLE IF EXISTS `ums_admin_role`;
DROP TABLE IF EXISTS `ai_action_execution`;
DROP TABLE IF EXISTS `oms_refund_record`;
DROP TABLE IF EXISTS `oms_order_return_item`;
DROP TABLE IF EXISTS `oms_logistics_event`;
DROP TABLE IF EXISTS `oms_shipment_item`;
DROP TABLE IF EXISTS `oms_shipment`;
DROP TABLE IF EXISTS `oms_order_request`;
DROP TABLE IF EXISTS `oms_order_return_apply`;
DROP TABLE IF EXISTS `oms_return_evidence`;
DROP TABLE IF EXISTS `oms_return_status_event`;
DROP TABLE IF EXISTS `ums_member_coupon`;
DROP TABLE IF EXISTS `sms_coupon`;
DROP TABLE IF EXISTS `oms_order_item`;
DROP TABLE IF EXISTS `oms_order`;
DROP TABLE IF EXISTS `oms_payment_record`;
DROP TABLE IF EXISTS `oms_cart_item`;
DROP TABLE IF EXISTS `pms_sku_stock`;
DROP TABLE IF EXISTS `pms_product_image`;
DROP TABLE IF EXISTS `pms_product_price_rule`;
DROP TABLE IF EXISTS `pms_category_attribute_template`;
DROP TABLE IF EXISTS `pms_brand`;
DROP TABLE IF EXISTS `pms_product_review`;
DROP TABLE IF EXISTS `ums_member_product_favorite`;
DROP TABLE IF EXISTS `ums_member_browse_history`;
DROP TABLE IF EXISTS `knowledge_doc_audit_log`;
DROP TABLE IF EXISTS `knowledge_quality_report`;
DROP TABLE IF EXISTS `knowledge_retrieval_test`;
DROP TABLE IF EXISTS `knowledge_task_event`;
DROP TABLE IF EXISTS `knowledge_index_task`;
DROP TABLE IF EXISTS `embedding_cache`;
DROP TABLE IF EXISTS `knowledge_chunk`;
DROP TABLE IF EXISTS `knowledge_doc_version`;
DROP TABLE IF EXISTS `knowledge_doc`;
DROP TABLE IF EXISTS `pms_product`;
DROP TABLE IF EXISTS `pms_product_category`;
DROP TABLE IF EXISTS `ums_member_address`;
DROP TABLE IF EXISTS `ums_member`;
DROP TABLE IF EXISTS `ums_member_login_history`;
DROP TABLE IF EXISTS `ums_member_device`;
DROP TABLE IF EXISTS `ums_admin`;

CREATE TABLE `ums_member` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(64) NOT NULL,
  `password` varchar(128) NOT NULL,
  `nickname` varchar(64) DEFAULT NULL,
  `phone` varchar(32) DEFAULT NULL,
  `email` varchar(254) DEFAULT NULL,
  `member_level` varchar(32) NOT NULL DEFAULT 'NORMAL',
  `status` tinyint NOT NULL DEFAULT 1,
  `privacy_consent_version` varchar(32) DEFAULT NULL,
  `privacy_consent_time` datetime DEFAULT NULL,
  `password_changed_at` datetime DEFAULT NULL,
  `cancelled_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ums_member_username` (`username`),
  UNIQUE KEY `uk_ums_member_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `ums_email_verification_code` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `email` varchar(254) NOT NULL,
  `purpose` varchar(32) NOT NULL,
  `code_hash` char(64) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE',
  `failed_attempts` int NOT NULL DEFAULT 0,
  `max_attempts` int NOT NULL DEFAULT 5,
  `expires_at` datetime NOT NULL,
  `last_sent_at` datetime NOT NULL,
  `consumed_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `active_key` varchar(300) GENERATED ALWAYS AS (
    CASE WHEN `status` = 'ACTIVE' THEN CONCAT(`purpose`, ':', `email`) ELSE NULL END
  ) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_email_verification_active` (`active_key`),
  KEY `idx_email_verification_expiry` (`status`, `expires_at`),
  KEY `idx_email_verification_email` (`email`, `purpose`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `ums_email_verification_send_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `email` varchar(254) NOT NULL,
  `purpose` varchar(32) NOT NULL,
  `client_ip` varchar(64) NOT NULL,
  `success` tinyint NOT NULL,
  `failure_reason` varchar(255) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_email_send_ip` (`client_ip`, `created_at`),
  KEY `idx_email_send_target` (`email`, `purpose`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `ums_member_login_history` (`id` bigint NOT NULL AUTO_INCREMENT,`member_id` bigint DEFAULT NULL,`username` varchar(64) NOT NULL,`client_ip` varchar(64) NOT NULL,`user_agent` varchar(500) DEFAULT NULL,`device_hash` varchar(64) NOT NULL,`success` tinyint NOT NULL,`risk_flag` tinyint NOT NULL DEFAULT 0,`failure_reason` varchar(255) DEFAULT NULL,`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(`id`),KEY `idx_member_login_history`(`member_id`,`create_time`),KEY `idx_member_login_risk`(`risk_flag`,`create_time`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `ums_member_device` (`id` bigint NOT NULL AUTO_INCREMENT,`member_id` bigint NOT NULL,`device_hash` varchar(64) NOT NULL,`device_name` varchar(100) NOT NULL,`last_ip` varchar(64) NOT NULL,`trusted` tinyint NOT NULL DEFAULT 0,`revoked` tinyint NOT NULL DEFAULT 0,`first_seen_time` datetime NOT NULL,`last_seen_time` datetime NOT NULL,PRIMARY KEY(`id`),UNIQUE KEY `uk_member_device`(`member_id`,`device_hash`),KEY `idx_member_device_active`(`member_id`,`revoked`,`last_seen_time`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `ums_admin_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role_code` varchar(64) NOT NULL,
  `role_name` varchar(100) NOT NULL,
  `status` tinyint NOT NULL DEFAULT 1,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `ums_admin_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `permission_code` varchar(100) NOT NULL,
  `permission_name` varchar(100) NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_permission_code` (`permission_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `ums_admin_role_relation` (
  `admin_id` bigint NOT NULL,
  `role_id` bigint NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`admin_id`, `role_id`),
  KEY `idx_admin_role_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `ums_role_permission_relation` (
  `role_id` bigint NOT NULL,
  `permission_id` bigint NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`role_id`, `permission_id`),
  KEY `idx_role_permission_permission` (`permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `ums_admin_login_attempt` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(64) NOT NULL,
  `client_ip` varchar(64) NOT NULL,
  `success` tinyint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_admin_login_attempt_guard` (`username`, `client_ip`, `success`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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

CREATE TABLE `ums_member_address` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `name` varchar(64) NOT NULL,
  `phone` varchar(32) NOT NULL,
  `province` varchar(64) NOT NULL,
  `city` varchar(64) NOT NULL,
  `region` varchar(64) NOT NULL,
  `detail_address` varchar(255) NOT NULL,
  `default_status` tinyint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ums_member_address_member_id` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `ums_admin` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(64) NOT NULL,
  `password` varchar(128) NOT NULL,
  `nick_name` varchar(64) DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT 1,
  `mfa_secret` varchar(128) DEFAULT NULL,
  `mfa_enabled` tinyint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ums_admin_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `pms_product_category` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `parent_id` bigint NOT NULL DEFAULT 0,
  `name` varchar(64) NOT NULL,
  `level` tinyint NOT NULL DEFAULT 0,
  `product_count` int NOT NULL DEFAULT 0,
  `product_unit` varchar(32) DEFAULT NULL,
  `nav_status` tinyint NOT NULL DEFAULT 1,
  `show_status` tinyint NOT NULL DEFAULT 1,
  `sort` int NOT NULL DEFAULT 0,
  `icon` varchar(500) DEFAULT NULL,
  `keywords` varchar(255) DEFAULT NULL,
  `description` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_pms_category_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `pms_brand` (
  `id` bigint NOT NULL AUTO_INCREMENT, `name` varchar(100) NOT NULL, `logo` varchar(500) DEFAULT NULL,
  `description` varchar(500) DEFAULT NULL, `sort` int NOT NULL DEFAULT 0, `status` tinyint NOT NULL DEFAULT 1,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_pms_brand_name` (`name`), KEY `idx_pms_brand_status` (`status`,`sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `pms_category_attribute_template` (
  `id` bigint NOT NULL AUTO_INCREMENT, `category_id` bigint NOT NULL, `template_name` varchar(100) NOT NULL,
  `schema_json` json NOT NULL, `status` tinyint NOT NULL DEFAULT 1,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `active_identity` tinyint GENERATED ALWAYS AS (CASE WHEN `status` = 1 THEN 1 ELSE NULL END) STORED,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_category_active_template` (`category_id`,`active_identity`),
  KEY `idx_attribute_template_category` (`category_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `pms_product` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `brand_id` bigint DEFAULT NULL,
  `category_id` bigint NOT NULL,
  `name` varchar(200) NOT NULL,
  `pic` varchar(500) DEFAULT NULL,
  `product_sn` varchar(64) NOT NULL,
  `delete_status` tinyint NOT NULL DEFAULT 0,
  `publish_status` tinyint NOT NULL DEFAULT 1,
  `new_status` tinyint NOT NULL DEFAULT 0,
  `recommand_status` tinyint NOT NULL DEFAULT 0,
  `verify_status` tinyint NOT NULL DEFAULT 1,
  `sort` int NOT NULL DEFAULT 0,
  `sale` int NOT NULL DEFAULT 0,
  `price` decimal(10,2) NOT NULL,
  `promotion_price` decimal(10,2) DEFAULT NULL,
  `gift_point` int NOT NULL DEFAULT 0,
  `sub_title` varchar(255) DEFAULT NULL,
  `original_price` decimal(10,2) DEFAULT NULL,
  `stock` int NOT NULL DEFAULT 0,
  `lock_stock` int NOT NULL DEFAULT 0,
  `low_stock` int NOT NULL DEFAULT 0,
  `unit` varchar(16) DEFAULT '件',
  `weight` decimal(10,2) DEFAULT 0.00,
  `keywords` varchar(255) DEFAULT NULL,
  `brand_name` varchar(100) DEFAULT NULL,
  `product_category_name` varchar(100) DEFAULT NULL,
  `description` text,
  `detail_desc` text,
  `detail_html` text,
  `detail_mobile_html` text,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pms_product_sn` (`product_sn`),
  KEY `idx_pms_product_category_id` (`category_id`),
  KEY `idx_pms_product_publish` (`publish_status`, `delete_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `pms_sku_stock` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint NOT NULL,
  `sku_code` varchar(64) NOT NULL,
  `price` decimal(10,2) NOT NULL,
  `stock` int NOT NULL DEFAULT 0,
  `low_stock` int NOT NULL DEFAULT 0,
  `pic` varchar(500) DEFAULT NULL,
  `sale` int NOT NULL DEFAULT 0,
  `promotion_price` decimal(10,2) DEFAULT NULL,
  `lock_stock` int NOT NULL DEFAULT 0,
  `sp_data` varchar(500) DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pms_sku_code` (`sku_code`),
  KEY `idx_pms_sku_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `pms_product_image` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint NOT NULL,
  `image_url` varchar(1000) NOT NULL,
  `sort` int NOT NULL DEFAULT 0,
  `is_primary` tinyint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_product_image_product` (`product_id`, `sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `pms_product_price_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint NOT NULL,
  `sku_id` bigint DEFAULT NULL,
  `rule_type` varchar(20) NOT NULL,
  `rule_name` varchar(100) NOT NULL,
  `member_level` varchar(32) DEFAULT NULL,
  `price` decimal(10,2) NOT NULL,
  `per_member_limit` int DEFAULT NULL,
  `priority` int NOT NULL DEFAULT 0,
  `status` tinyint NOT NULL DEFAULT 1,
  `start_time` datetime NOT NULL,
  `end_time` datetime NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_price_rule_lookup` (`product_id`, `sku_id`, `status`, `start_time`, `end_time`),
  KEY `idx_price_rule_member` (`member_level`, `status`, `start_time`, `end_time`),
  CONSTRAINT `chk_price_rule_type` CHECK (`rule_type` IN ('MEMBER', 'ACTIVITY')),
  CONSTRAINT `chk_price_rule_price` CHECK (`price` >= 0),
  CONSTRAINT `chk_price_rule_limit` CHECK (`per_member_limit` IS NULL OR `per_member_limit` >= 0),
  CONSTRAINT `chk_price_rule_window` CHECK (`end_time` > `start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `pms_product_review` (`id` bigint NOT NULL AUTO_INCREMENT,`member_id` bigint NOT NULL,`product_id` bigint NOT NULL,`order_item_id` bigint NOT NULL,`rating` tinyint NOT NULL,`content` varchar(2000) NOT NULL DEFAULT '',`status` tinyint NOT NULL DEFAULT 1,`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(`id`),UNIQUE KEY `uk_review_order_item`(`order_item_id`),KEY `idx_review_product`(`product_id`,`status`,`create_time`),CONSTRAINT `chk_review_rating` CHECK(`rating` BETWEEN 1 AND 5)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `ums_member_product_favorite` (`id` bigint NOT NULL AUTO_INCREMENT,`member_id` bigint NOT NULL,`product_id` bigint NOT NULL,`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(`id`),UNIQUE KEY `uk_favorite_member_product`(`member_id`,`product_id`),KEY `idx_favorite_member`(`member_id`,`create_time`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `ums_member_browse_history` (`id` bigint NOT NULL AUTO_INCREMENT,`member_id` bigint NOT NULL,`product_id` bigint NOT NULL,`view_count` int NOT NULL DEFAULT 1,`last_view_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(`id`),UNIQUE KEY `uk_browse_member_product`(`member_id`,`product_id`),KEY `idx_browse_member_time`(`member_id`,`last_view_time`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `sms_coupon` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL,
  `type` varchar(32) NOT NULL DEFAULT 'FULL_REDUCTION',
  `amount` decimal(10,2) NOT NULL DEFAULT 0.00,
  `min_point` decimal(10,2) NOT NULL DEFAULT 0.00,
  `platform` varchar(32) NOT NULL DEFAULT 'ALL',
  `note` varchar(255) DEFAULT NULL,
  `start_time` datetime NOT NULL,
  `end_time` datetime NOT NULL,
  `status` tinyint NOT NULL DEFAULT 1,
  `total_quantity` int NOT NULL DEFAULT 0,
  `remaining_quantity` int NOT NULL DEFAULT 0,
  `per_member_limit` int NOT NULL DEFAULT 1,
  `receive_start_time` datetime DEFAULT NULL,
  `receive_end_time` datetime DEFAULT NULL,
  `scope_type` varchar(20) NOT NULL DEFAULT 'ALL',
  `scope_ids` varchar(1000) DEFAULT NULL,
  `budget_amount` decimal(14,2) DEFAULT NULL,
  `used_budget` decimal(14,2) NOT NULL DEFAULT 0.00,
  `refund_policy` varchar(20) NOT NULL DEFAULT 'RETURN',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `ums_member_coupon` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `coupon_id` bigint NOT NULL,
  `coupon_name` varchar(100) NOT NULL,
  `claim_no` int NOT NULL DEFAULT 1,
  `status` tinyint NOT NULL DEFAULT 0,
  `used_time` datetime DEFAULT NULL,
  `order_id` bigint DEFAULT NULL,
  `order_sn` varchar(64) DEFAULT NULL,
  `actual_discount_amount` decimal(10,2) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_coupon_claim` (`member_id`, `coupon_id`, `claim_no`),
  KEY `idx_member_coupon_member_id` (`member_id`),
  KEY `idx_member_coupon_coupon_id` (`coupon_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `oms_cart_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint NOT NULL,
  `product_sku_id` bigint DEFAULT NULL,
  `member_id` bigint NOT NULL,
  `quantity` int NOT NULL DEFAULT 1,
  `price` decimal(10,2) NOT NULL,
  `product_pic` varchar(500) DEFAULT NULL,
  `product_name` varchar(200) NOT NULL,
  `product_sub_title` varchar(255) DEFAULT NULL,
  `product_sku_code` varchar(64) DEFAULT NULL,
  `member_nickname` varchar(64) DEFAULT NULL,
  `create_date` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `modify_date` datetime DEFAULT NULL,
  `delete_status` tinyint NOT NULL DEFAULT 0,
  `product_category_name` varchar(100) DEFAULT NULL,
  `product_brand` varchar(100) DEFAULT NULL,
  `product_sn` varchar(64) DEFAULT NULL,
  `product_attr` varchar(500) DEFAULT NULL,
  `sku_identity` bigint GENERATED ALWAYS AS (COALESCE(`product_sku_id`, 0)) STORED,
  `active_identity` tinyint GENERATED ALWAYS AS (CASE WHEN `delete_status` = 0 THEN 1 ELSE NULL END) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cart_active_item` (`member_id`, `product_id`, `sku_identity`, `active_identity`),
  KEY `idx_oms_cart_item_member_id` (`member_id`),
  KEY `idx_oms_cart_item_product_sku` (`product_id`, `product_sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `oms_order_return_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `return_apply_id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `order_item_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `product_sku_id` bigint DEFAULT NULL,
  `quantity` int NOT NULL,
  `refund_amount` decimal(10,2) NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_return_apply_order_item` (`return_apply_id`, `order_item_id`),
  KEY `idx_return_item_order_item` (`order_item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `oms_order` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `member_username` varchar(64) DEFAULT NULL,
  `order_sn` varchar(64) NOT NULL,
  `total_amount` decimal(10,2) NOT NULL DEFAULT 0.00,
  `pay_amount` decimal(10,2) NOT NULL DEFAULT 0.00,
  `freight_amount` decimal(10,2) NOT NULL DEFAULT 0.00,
  `promotion_amount` decimal(10,2) NOT NULL DEFAULT 0.00,
  `coupon_amount` decimal(10,2) NOT NULL DEFAULT 0.00,
  `discount_amount` decimal(10,2) NOT NULL DEFAULT 0.00,
  `pay_type` tinyint NOT NULL DEFAULT 0,
  `source_type` tinyint NOT NULL DEFAULT 0,
  `status` tinyint NOT NULL DEFAULT 0,
  `order_type` tinyint NOT NULL DEFAULT 0,
  `delivery_company` varchar(64) DEFAULT NULL,
  `delivery_sn` varchar(64) DEFAULT NULL,
  `promotion_info` varchar(255) DEFAULT NULL,
  `receiver_name` varchar(64) DEFAULT NULL,
  `receiver_phone` varchar(32) DEFAULT NULL,
  `receiver_province` varchar(64) DEFAULT NULL,
  `receiver_city` varchar(64) DEFAULT NULL,
  `receiver_region` varchar(64) DEFAULT NULL,
  `receiver_detail_address` varchar(255) DEFAULT NULL,
  `note` varchar(500) DEFAULT NULL,
  `confirm_status` tinyint NOT NULL DEFAULT 0,
  `delete_status` tinyint NOT NULL DEFAULT 0,
  `refund_status` varchar(32) NOT NULL DEFAULT 'NONE',
  `refunded_amount` decimal(10,2) NOT NULL DEFAULT 0.00,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `payment_time` datetime DEFAULT NULL,
  `delivery_time` datetime DEFAULT NULL,
  `receive_time` datetime DEFAULT NULL,
  `modify_time` datetime DEFAULT NULL,
  `expire_time` datetime DEFAULT NULL,
  `inventory_reservation_status` varchar(20) NOT NULL DEFAULT 'NOT_RESERVED',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_oms_order_order_sn` (`order_sn`),
  KEY `idx_oms_order_member_id` (`member_id`),
  KEY `idx_oms_order_expiration` (`status`, `expire_time`),
  KEY `idx_oms_order_inventory_status` (`inventory_reservation_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `oms_order_request` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `request_id` varchar(64) NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'PROCESSING',
  `order_id` bigint DEFAULT NULL,
  `order_sn` varchar(64) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_oms_order_request_member` (`member_id`, `request_id`),
  KEY `idx_oms_order_request_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `oms_payment_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `order_sn` varchar(64) NOT NULL,
  `pay_channel` varchar(32) NOT NULL DEFAULT 'SIMULATE',
  `pay_status` varchar(32) NOT NULL DEFAULT 'WAIT_PAY',
  `amount` decimal(10,2) NOT NULL DEFAULT 0.00,
  `refunded_amount` decimal(10,2) NOT NULL DEFAULT 0.00,
  `transaction_no` varchar(64) DEFAULT NULL,
  `pay_time` datetime DEFAULT NULL,
  `callback_time` datetime DEFAULT NULL,
  `raw_callback` text,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_oms_payment_order_id` (`order_id`)
  ,UNIQUE KEY `uk_oms_payment_transaction_no` (`transaction_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `oms_order_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `order_sn` varchar(64) NOT NULL,
  `product_id` bigint NOT NULL,
  `product_pic` varchar(500) DEFAULT NULL,
  `product_name` varchar(200) NOT NULL,
  `product_brand` varchar(100) DEFAULT NULL,
  `product_sn` varchar(64) DEFAULT NULL,
  `product_price` decimal(10,2) NOT NULL,
  `product_quantity` int NOT NULL DEFAULT 1,
  `product_sku_id` bigint DEFAULT NULL,
  `product_sku_code` varchar(64) DEFAULT NULL,
  `product_category_id` bigint DEFAULT NULL,
  `promotion_name` varchar(100) DEFAULT NULL,
  `promotion_amount` decimal(10,2) NOT NULL DEFAULT 0.00,
  `coupon_amount` decimal(10,2) NOT NULL DEFAULT 0.00,
  `real_amount` decimal(10,2) NOT NULL DEFAULT 0.00,
  `product_attr` varchar(500) DEFAULT NULL,
  `return_reserved_quantity` int NOT NULL DEFAULT 0,
  `refunded_quantity` int NOT NULL DEFAULT 0,
  `shipped_quantity` int NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_oms_order_item_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `oms_shipment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `shipment_sn` varchar(64) NOT NULL,
  `order_id` bigint NOT NULL,
  `order_sn` varchar(64) NOT NULL,
  `carrier_code` varchar(32) NOT NULL,
  `carrier_name` varchar(64) NOT NULL,
  `tracking_no` varchar(100) NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'SHIPPED',
  `shipped_at` datetime NOT NULL,
  `delivered_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_shipment_sn` (`shipment_sn`),
  UNIQUE KEY `uk_shipment_tracking` (`carrier_code`, `tracking_no`),
  KEY `idx_shipment_order` (`order_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `oms_shipment_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `shipment_id` bigint NOT NULL,
  `order_item_id` bigint NOT NULL,
  `quantity` int NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_shipment_order_item` (`shipment_id`, `order_item_id`),
  KEY `idx_shipment_item_order_item` (`order_item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `oms_logistics_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `shipment_id` bigint NOT NULL,
  `event_code` varchar(32) NOT NULL,
  `event_time` datetime NOT NULL,
  `location` varchar(255) DEFAULT NULL,
  `description` varchar(500) NOT NULL,
  `source` varchar(32) NOT NULL DEFAULT 'ADMIN',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_logistics_event_shipment` (`shipment_id`, `event_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `oms_order_return_apply` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `order_sn` varchar(64) NOT NULL,
  `member_id` bigint NOT NULL,
  `status` tinyint NOT NULL DEFAULT 0,
  `type` varchar(32) NOT NULL DEFAULT 'REFUND',
  `reason` varchar(255) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `return_amount` decimal(10,2) NOT NULL DEFAULT 0.00,
  `handle_note` varchar(500) DEFAULT NULL,
  `return_carrier` varchar(64) DEFAULT NULL,
  `return_tracking_no` varchar(100) DEFAULT NULL,
  `inspection_result` varchar(32) DEFAULT NULL,
  `inspection_note` varchar(500) DEFAULT NULL,
  `sla_deadline` datetime DEFAULT NULL,
  `sla_overdue` tinyint NOT NULL DEFAULT 0,
  `received_time` datetime DEFAULT NULL,
  `handle_time` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `active_identity` tinyint GENERATED ALWAYS AS (CASE WHEN `status` IN (0, 1, 5, 7, 8) THEN 1 ELSE NULL END) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_return_active_order_type` (`order_id`, `type`, `active_identity`),
  KEY `idx_oms_return_member_id` (`member_id`),
  KEY `idx_oms_return_order_id` (`order_id`)
  ,KEY `idx_return_sla` (`status`,`sla_overdue`,`sla_deadline`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `oms_return_evidence` (`id` bigint NOT NULL AUTO_INCREMENT,`return_apply_id` bigint NOT NULL,`member_id` bigint NOT NULL,`media_type` varchar(16) NOT NULL,`media_url` varchar(1000) NOT NULL,`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(`id`),KEY `idx_return_evidence_apply`(`return_apply_id`,`create_time`),CONSTRAINT `chk_return_media_type` CHECK(`media_type` IN('IMAGE','VIDEO'))) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `oms_return_status_event` (`id` bigint NOT NULL AUTO_INCREMENT,`return_apply_id` bigint NOT NULL,`from_status` tinyint DEFAULT NULL,`to_status` tinyint DEFAULT NULL,`event_type` varchar(32) NOT NULL,`operator_id` bigint DEFAULT NULL,`operator_type` varchar(16) NOT NULL,`note` varchar(500) DEFAULT NULL,`create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(`id`),KEY `idx_return_event_apply`(`return_apply_id`,`create_time`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `oms_refund_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `request_id` varchar(64) NOT NULL,
  `return_apply_id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `order_sn` varchar(64) NOT NULL,
  `refund_channel` varchar(32) NOT NULL,
  `refund_status` varchar(32) NOT NULL DEFAULT 'PENDING',
  `amount` decimal(10,2) NOT NULL,
  `refund_transaction_no` varchar(64) DEFAULT NULL,
  `failure_reason` varchar(500) DEFAULT NULL,
  `handle_note` varchar(500) DEFAULT NULL,
  `retry_count` int NOT NULL DEFAULT 0,
  `max_retry` int NOT NULL DEFAULT 8,
  `manual_retry_count` int NOT NULL DEFAULT 0,
  `next_retry_time` datetime DEFAULT NULL,
  `channel_called_time` datetime DEFAULT NULL,
  `finished_time` datetime DEFAULT NULL,
  `closed_time` datetime DEFAULT NULL,
  `closed_reason` varchar(500) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_request_id` (`request_id`),
  UNIQUE KEY `uk_refund_return_apply` (`return_apply_id`),
  KEY `idx_refund_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `internal_request_nonce` (
  `nonce` varchar(64) NOT NULL,
  `expires_at` datetime NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`nonce`),
  KEY `idx_internal_nonce_expire` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `knowledge_doc` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default',
  `title` varchar(200) NOT NULL,
  `source_type` varchar(50) NOT NULL DEFAULT 'POLICY',
  `content` text,
  `status` varchar(32) NOT NULL DEFAULT 'UPLOADED',
  `version` int NOT NULL DEFAULT 1,
  `current_version_id` bigint DEFAULT NULL,
  `source_system` varchar(64) NOT NULL DEFAULT 'manual',
  `source_trust_score` decimal(4,3) NOT NULL DEFAULT 0.500,
  `source_uri` varchar(500) DEFAULT NULL,
  `source_hash` varchar(64) DEFAULT NULL,
  `external_doc_id` varchar(128) DEFAULT NULL,
  `visibility_scope` varchar(64) NOT NULL DEFAULT 'PUBLIC_USER',
  `role_scope` varchar(255) DEFAULT NULL,
  `category_ids` varchar(500) DEFAULT NULL,
  `activity_id` varchar(64) DEFAULT NULL,
  `tags` varchar(500) DEFAULT NULL,
  `owner_user_id` bigint DEFAULT NULL,
  `effective_time` datetime DEFAULT NULL,
  `expire_time` datetime DEFAULT NULL,
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_knowledge_doc_status` (`status`),
  KEY `idx_knowledge_doc_scope` (`tenant_id`, `visibility_scope`),
  KEY `idx_knowledge_doc_effective` (`effective_time`, `expire_time`),
  KEY `idx_knowledge_doc_source_hash` (`source_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `knowledge_doc_version` (
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
  KEY `idx_knowledge_doc_version_hash` (`source_hash`),
  CONSTRAINT `fk_knowledge_doc_version_doc` FOREIGN KEY (`doc_id`) REFERENCES `knowledge_doc` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `knowledge_chunk` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_id` bigint NOT NULL,
  `doc_version_id` bigint DEFAULT NULL,
  `doc_version` int NOT NULL,
  `chunk_key` varchar(255) NOT NULL,
  `chunk_type` varchar(32) NOT NULL DEFAULT 'TEXT',
  `chunk_no` int NOT NULL,
  `parent_chunk_id` bigint DEFAULT NULL,
  `prev_chunk_id` bigint DEFAULT NULL,
  `next_chunk_id` bigint DEFAULT NULL,
  `title` varchar(200) NOT NULL,
  `section_title` varchar(200) DEFAULT NULL,
  `section_path` json DEFAULT NULL,
  `original_content` mediumtext,
  `masked_content` mediumtext,
  `index_content` mediumtext,
  `snippet` varchar(1000) DEFAULT NULL,
  `token_count` int NOT NULL DEFAULT 0,
  `content_hash` varchar(64) NOT NULL,
  `chunk_hash` varchar(64) DEFAULT NULL,
  `page_start` int DEFAULT NULL,
  `page_end` int DEFAULT NULL,
  `source_type` varchar(50) NOT NULL DEFAULT 'POLICY',
  `source_ref` varchar(128) DEFAULT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE',
  `visibility_scope` varchar(64) NOT NULL DEFAULT 'PUBLIC_USER',
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default',
  `role_scope` varchar(255) DEFAULT NULL,
  `category_ids` varchar(500) DEFAULT NULL,
  `activity_id` varchar(64) DEFAULT NULL,
  `effective_time` datetime DEFAULT NULL,
  `expire_time` datetime DEFAULT NULL,
  `keyword_index_version` varchar(64) DEFAULT NULL,
  `embedding_id` varchar(128) DEFAULT NULL,
  `embedding_model` varchar(128) DEFAULT NULL,
  `embedding_model_version` varchar(64) DEFAULT NULL,
  `embedding_sync_status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `vector_delete_retry_count` int NOT NULL DEFAULT 0,
  `vector_delete_next_retry_at` datetime DEFAULT NULL,
  `vector_delete_claim_token` varchar(64) DEFAULT NULL,
  `vector_delete_claim_until` datetime DEFAULT NULL,
  `vector_delete_last_error` varchar(1000) DEFAULT NULL,
  `vector_collection` varchar(128) DEFAULT NULL,
  `index_version` varchar(64) NOT NULL DEFAULT 'index-v1',
  `chunk_strategy_version` varchar(64) NOT NULL DEFAULT 'chunk-v1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_chunk_key` (`chunk_key`),
  KEY `idx_knowledge_chunk_doc` (`doc_id`, `doc_version`, `chunk_no`),
  KEY `idx_knowledge_chunk_version` (`doc_version_id`),
  KEY `idx_knowledge_chunk_status` (`status`),
  KEY `idx_knowledge_chunk_scope` (`tenant_id`, `visibility_scope`),
  KEY `idx_knowledge_chunk_effective` (`effective_time`, `expire_time`),
  KEY `idx_knowledge_chunk_hash` (`content_hash`),
  KEY `idx_knowledge_chunk_embedding_sync` (`embedding_sync_status`),
  KEY `idx_knowledge_chunk_vector_delete` (`embedding_sync_status`, `vector_delete_next_retry_at`, `id`),
  CONSTRAINT `fk_knowledge_chunk_doc` FOREIGN KEY (`doc_id`) REFERENCES `knowledge_doc` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_knowledge_chunk_doc_version` FOREIGN KEY (`doc_version_id`) REFERENCES `knowledge_doc_version` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `embedding_cache` (
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

CREATE TABLE `knowledge_index_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_id` varchar(64) NOT NULL,
  `batch_id` varchar(64) DEFAULT NULL,
  `doc_id` bigint DEFAULT NULL,
  `doc_version_id` bigint DEFAULT NULL,
  `chunk_id` bigint DEFAULT NULL,
  `task_type` varchar(32) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `execution_token` varchar(64) DEFAULT NULL,
  `attempt_no` int NOT NULL DEFAULT 0,
  `current_step` varchar(64) DEFAULT NULL,
  `progress_current` int NOT NULL DEFAULT 0,
  `progress_total` int NOT NULL DEFAULT 0,
  `trigger_type` varchar(32) NOT NULL DEFAULT 'MANUAL',
  `queue_name` varchar(64) NOT NULL DEFAULT 'DEFAULT',
  `shard_key` varchar(128) DEFAULT NULL,
  `priority` int NOT NULL DEFAULT 100,
  `retry_count` int NOT NULL DEFAULT 0,
  `max_retry` int NOT NULL DEFAULT 3,
  `alert_enabled` tinyint(1) NOT NULL DEFAULT 1,
  `alert_channel` varchar(128) DEFAULT NULL,
  `chunk_strategy_version` varchar(64) DEFAULT NULL,
  `embedding_model` varchar(128) DEFAULT NULL,
  `embedding_model_version` varchar(64) DEFAULT NULL,
  `index_version` varchar(64) DEFAULT NULL,
  `locked_by` varchar(128) DEFAULT NULL,
  `lock_until` datetime DEFAULT NULL,
  `timeout_at` datetime DEFAULT NULL,
  `next_retry_at` datetime DEFAULT NULL,
  `last_heartbeat_at` datetime DEFAULT NULL,
  `error_code` varchar(64) DEFAULT NULL,
  `error_message` varchar(1000) DEFAULT NULL,
  `dead_letter_reason` varchar(1000) DEFAULT NULL,
  `started_at` datetime DEFAULT NULL,
  `finished_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `active_doc_version_id` bigint GENERATED ALWAYS AS (
      CASE
          WHEN `task_type` = 'PROCESS_DOC_UPLOAD'
               AND `status` IN ('PENDING', 'DISPATCHING', 'RUNNING', 'RETRY_WAIT')
          THEN `doc_version_id`
          ELSE NULL
      END
  ) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_knowledge_index_task_id` (`task_id`),
  UNIQUE KEY `uk_knowledge_index_task_active_version` (`active_doc_version_id`),
  KEY `idx_knowledge_index_task_doc` (`doc_id`, `task_type`, `status`),
  KEY `idx_knowledge_index_task_version` (`doc_version_id`, `task_type`, `status`),
  KEY `idx_knowledge_index_task_chunk` (`chunk_id`, `task_type`, `status`),
  KEY `idx_knowledge_index_task_batch` (`batch_id`),
  KEY `idx_knowledge_index_task_status` (`status`, `priority`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `knowledge_task_event` (
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

CREATE TABLE `knowledge_retrieval_test` (
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

CREATE TABLE `knowledge_quality_report` (
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

CREATE TABLE `knowledge_doc_audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doc_id` bigint DEFAULT NULL,
  `chunk_id` bigint DEFAULT NULL,
  `action` varchar(32) NOT NULL,
  `operator_id` bigint DEFAULT NULL,
  `operator_role` varchar(64) DEFAULT NULL,
  `before_snapshot_json` json DEFAULT NULL,
  `after_snapshot_json` json DEFAULT NULL,
  `reason` varchar(500) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_knowledge_doc_audit_doc` (`doc_id`, `created_at`),
  KEY `idx_knowledge_doc_audit_chunk` (`chunk_id`, `created_at`),
  KEY `idx_knowledge_doc_audit_action` (`action`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `ai_action_execution` (
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

SET FOREIGN_KEY_CHECKS = 1;
