CREATE DATABASE IF NOT EXISTS aimall DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE aimall;

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `ums_member_address` (
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

CREATE TABLE IF NOT EXISTS `pms_sku_stock` (
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
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pms_sku_code` (`sku_code`),
  KEY `idx_pms_sku_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `sms_home_recommend_product` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint NOT NULL,
  `product_name` varchar(200) NOT NULL,
  `recommend_status` tinyint NOT NULL DEFAULT 1,
  `sort` int NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_sms_home_recommend_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `sms_coupon` (
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
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ums_member_coupon` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `coupon_id` bigint NOT NULL,
  `coupon_name` varchar(100) NOT NULL,
  `status` tinyint NOT NULL DEFAULT 0,
  `used_time` datetime DEFAULT NULL,
  `order_id` bigint DEFAULT NULL,
  `order_sn` varchar(64) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_member_coupon_member_id` (`member_id`),
  KEY `idx_member_coupon_coupon_id` (`coupon_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `oms_payment_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `order_sn` varchar(64) NOT NULL,
  `pay_channel` varchar(32) NOT NULL DEFAULT 'SIMULATE',
  `pay_status` varchar(32) NOT NULL DEFAULT 'WAIT_PAY',
  `amount` decimal(10,2) NOT NULL DEFAULT 0.00,
  `transaction_no` varchar(64) DEFAULT NULL,
  `pay_time` datetime DEFAULT NULL,
  `callback_time` datetime DEFAULT NULL,
  `raw_callback` text,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_oms_payment_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `oms_order_return_apply` (
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
  `handle_time` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_oms_return_member_id` (`member_id`),
  KEY `idx_oms_return_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELIMITER $$

DROP PROCEDURE IF EXISTS add_column_if_missing $$
CREATE PROCEDURE add_column_if_missing(
  IN p_table_name varchar(64),
  IN p_column_name varchar(64),
  IN p_column_definition text
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = p_table_name
      AND COLUMN_NAME = p_column_name
  ) THEN
    SET @ddl = CONCAT(
      'ALTER TABLE `', p_table_name, '` ADD COLUMN `', p_column_name, '` ', p_column_definition
    );
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

CALL add_column_if_missing('pms_product_category', 'product_count', 'int NOT NULL DEFAULT 0');
CALL add_column_if_missing('pms_product_category', 'product_unit', 'varchar(32) DEFAULT NULL');
CALL add_column_if_missing('pms_product_category', 'nav_status', 'tinyint NOT NULL DEFAULT 1');
CALL add_column_if_missing('pms_product_category', 'show_status', 'tinyint NOT NULL DEFAULT 1');
CALL add_column_if_missing('pms_product_category', 'icon', 'varchar(500) DEFAULT NULL');
CALL add_column_if_missing('pms_product_category', 'keywords', 'varchar(255) DEFAULT NULL');
CALL add_column_if_missing('pms_product_category', 'description', 'varchar(500) DEFAULT NULL');

CALL add_column_if_missing('pms_product', 'brand_id', 'bigint DEFAULT NULL');
CALL add_column_if_missing('pms_product', 'product_sn', 'varchar(64) DEFAULT NULL');
CALL add_column_if_missing('pms_product', 'delete_status', 'tinyint NOT NULL DEFAULT 0');
CALL add_column_if_missing('pms_product', 'publish_status', 'tinyint NOT NULL DEFAULT 1');
CALL add_column_if_missing('pms_product', 'new_status', 'tinyint NOT NULL DEFAULT 0');
CALL add_column_if_missing('pms_product', 'recommand_status', 'tinyint NOT NULL DEFAULT 0');
CALL add_column_if_missing('pms_product', 'verify_status', 'tinyint NOT NULL DEFAULT 1');
CALL add_column_if_missing('pms_product', 'sort', 'int NOT NULL DEFAULT 0');
CALL add_column_if_missing('pms_product', 'sale', 'int NOT NULL DEFAULT 0');
CALL add_column_if_missing('pms_product', 'promotion_price', 'decimal(10,2) DEFAULT NULL');
CALL add_column_if_missing('pms_product', 'gift_point', 'int NOT NULL DEFAULT 0');
CALL add_column_if_missing('pms_product', 'sub_title', 'varchar(255) DEFAULT NULL');
CALL add_column_if_missing('pms_product', 'original_price', 'decimal(10,2) DEFAULT NULL');
CALL add_column_if_missing('pms_product', 'low_stock', 'int NOT NULL DEFAULT 0');
CALL add_column_if_missing('pms_product', 'unit', 'varchar(16) DEFAULT ''item''');
CALL add_column_if_missing('pms_product', 'weight', 'decimal(10,2) DEFAULT 0.00');
CALL add_column_if_missing('pms_product', 'keywords', 'varchar(255) DEFAULT NULL');
CALL add_column_if_missing('pms_product', 'brand_name', 'varchar(100) DEFAULT NULL');
CALL add_column_if_missing('pms_product', 'product_category_name', 'varchar(100) DEFAULT NULL');
CALL add_column_if_missing('pms_product', 'detail_desc', 'text');
CALL add_column_if_missing('pms_product', 'detail_html', 'text');
CALL add_column_if_missing('pms_product', 'detail_mobile_html', 'text');

CALL add_column_if_missing('oms_cart_item', 'product_sku_id', 'bigint DEFAULT NULL');
CALL add_column_if_missing('oms_cart_item', 'price', 'decimal(10,2) NOT NULL DEFAULT 0.00');
CALL add_column_if_missing('oms_cart_item', 'product_pic', 'varchar(500) DEFAULT NULL');
CALL add_column_if_missing('oms_cart_item', 'product_sub_title', 'varchar(255) DEFAULT NULL');
CALL add_column_if_missing('oms_cart_item', 'product_sku_code', 'varchar(64) DEFAULT NULL');
CALL add_column_if_missing('oms_cart_item', 'member_nickname', 'varchar(64) DEFAULT NULL');
CALL add_column_if_missing('oms_cart_item', 'product_category_name', 'varchar(100) DEFAULT NULL');
CALL add_column_if_missing('oms_cart_item', 'product_brand', 'varchar(100) DEFAULT NULL');
CALL add_column_if_missing('oms_cart_item', 'product_sn', 'varchar(64) DEFAULT NULL');
CALL add_column_if_missing('oms_cart_item', 'product_attr', 'varchar(500) DEFAULT NULL');

CALL add_column_if_missing('oms_order', 'member_username', 'varchar(64) DEFAULT NULL');
CALL add_column_if_missing('oms_order', 'freight_amount', 'decimal(10,2) NOT NULL DEFAULT 0.00');
CALL add_column_if_missing('oms_order', 'promotion_amount', 'decimal(10,2) NOT NULL DEFAULT 0.00');
CALL add_column_if_missing('oms_order', 'coupon_amount', 'decimal(10,2) NOT NULL DEFAULT 0.00');
CALL add_column_if_missing('oms_order', 'discount_amount', 'decimal(10,2) NOT NULL DEFAULT 0.00');
CALL add_column_if_missing('oms_order', 'source_type', 'tinyint NOT NULL DEFAULT 0');
CALL add_column_if_missing('oms_order', 'order_type', 'tinyint NOT NULL DEFAULT 0');
CALL add_column_if_missing('oms_order', 'delivery_company', 'varchar(64) DEFAULT NULL');
CALL add_column_if_missing('oms_order', 'delivery_sn', 'varchar(64) DEFAULT NULL');
CALL add_column_if_missing('oms_order', 'promotion_info', 'varchar(255) DEFAULT NULL');
CALL add_column_if_missing('oms_order', 'receiver_name', 'varchar(64) DEFAULT NULL');
CALL add_column_if_missing('oms_order', 'receiver_phone', 'varchar(32) DEFAULT NULL');
CALL add_column_if_missing('oms_order', 'receiver_province', 'varchar(64) DEFAULT NULL');
CALL add_column_if_missing('oms_order', 'receiver_city', 'varchar(64) DEFAULT NULL');
CALL add_column_if_missing('oms_order', 'receiver_region', 'varchar(64) DEFAULT NULL');
CALL add_column_if_missing('oms_order', 'receiver_detail_address', 'varchar(255) DEFAULT NULL');
CALL add_column_if_missing('oms_order', 'note', 'varchar(500) DEFAULT NULL');
CALL add_column_if_missing('oms_order', 'confirm_status', 'tinyint NOT NULL DEFAULT 0');
CALL add_column_if_missing('oms_order', 'delete_status', 'tinyint NOT NULL DEFAULT 0');
CALL add_column_if_missing('oms_order', 'delivery_time', 'datetime DEFAULT NULL');
CALL add_column_if_missing('oms_order', 'receive_time', 'datetime DEFAULT NULL');
CALL add_column_if_missing('oms_order', 'modify_time', 'datetime DEFAULT NULL');

CALL add_column_if_missing('oms_order_item', 'order_sn', 'varchar(64) DEFAULT NULL');
CALL add_column_if_missing('oms_order_item', 'product_pic', 'varchar(500) DEFAULT NULL');
CALL add_column_if_missing('oms_order_item', 'product_brand', 'varchar(100) DEFAULT NULL');
CALL add_column_if_missing('oms_order_item', 'product_sn', 'varchar(64) DEFAULT NULL');
CALL add_column_if_missing('oms_order_item', 'product_sku_id', 'bigint DEFAULT NULL');
CALL add_column_if_missing('oms_order_item', 'product_sku_code', 'varchar(64) DEFAULT NULL');
CALL add_column_if_missing('oms_order_item', 'product_category_id', 'bigint DEFAULT NULL');
CALL add_column_if_missing('oms_order_item', 'promotion_name', 'varchar(100) DEFAULT NULL');
CALL add_column_if_missing('oms_order_item', 'promotion_amount', 'decimal(10,2) NOT NULL DEFAULT 0.00');
CALL add_column_if_missing('oms_order_item', 'coupon_amount', 'decimal(10,2) NOT NULL DEFAULT 0.00');
CALL add_column_if_missing('oms_order_item', 'real_amount', 'decimal(10,2) NOT NULL DEFAULT 0.00');
CALL add_column_if_missing('oms_order_item', 'product_attr', 'varchar(500) DEFAULT NULL');

CALL add_column_if_missing('knowledge_doc', 'source_type', 'varchar(50) NOT NULL DEFAULT ''POLICY''');
CALL add_column_if_missing('knowledge_doc', 'status', 'varchar(20) NOT NULL DEFAULT ''ENABLED''');
CALL add_column_if_missing('knowledge_doc', 'version', 'int NOT NULL DEFAULT 1');

DROP PROCEDURE IF EXISTS add_column_if_missing;

UPDATE `pms_product`
SET `product_sn` = CONCAT('MIGRATED-', `id`)
WHERE `product_sn` IS NULL OR `product_sn` = '';

UPDATE `pms_product`
SET `publish_status` = 1
WHERE `publish_status` IS NULL;

UPDATE `knowledge_doc`
SET `status` = 'ENABLED'
WHERE `status` IS NULL OR `status` = '';

INSERT INTO `ums_admin` (`id`, `username`, `password`, `nick_name`, `status`, `create_time`)
SELECT 1, 'admin', '', 'admin', 1, NOW()
WHERE NOT EXISTS (
  SELECT 1 FROM `ums_admin` WHERE `id` = 1 OR `username` = 'admin'
);
