-- Existing MySQL 8 database migration for transaction-safety fields.
ALTER TABLE `pms_product`
    ADD COLUMN `lock_stock` int NOT NULL DEFAULT 0 AFTER `stock`;

CREATE TABLE IF NOT EXISTS `oms_order_request` (
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

DELETE mc
FROM `ums_member_coupon` mc
LEFT JOIN `ums_member` m ON m.id = mc.member_id
WHERE m.id IS NULL;

ALTER TABLE `ums_member_coupon`
    ADD UNIQUE KEY `uk_member_coupon_member_coupon` (`member_id`, `coupon_id`);

ALTER TABLE `oms_order`
    ADD COLUMN `refund_status` varchar(32) NOT NULL DEFAULT 'NONE' AFTER `delete_status`,
    ADD COLUMN `refunded_amount` decimal(10,2) NOT NULL DEFAULT 0.00 AFTER `refund_status`;

CREATE TABLE IF NOT EXISTS `oms_refund_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `request_id` varchar(64) NOT NULL,
  `return_apply_id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `order_sn` varchar(64) NOT NULL,
  `refund_channel` varchar(32) NOT NULL,
  `refund_status` varchar(32) NOT NULL DEFAULT 'PROCESSING',
  `amount` decimal(10,2) NOT NULL,
  `refund_transaction_no` varchar(64) DEFAULT NULL,
  `failure_reason` varchar(500) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_request_id` (`request_id`),
  UNIQUE KEY `uk_refund_return_apply` (`return_apply_id`),
  KEY `idx_refund_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
