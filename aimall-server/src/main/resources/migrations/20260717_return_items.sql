ALTER TABLE `oms_order_item`
    ADD COLUMN `return_reserved_quantity` int NOT NULL DEFAULT 0 AFTER `product_attr`,
    ADD COLUMN `refunded_quantity` int NOT NULL DEFAULT 0 AFTER `return_reserved_quantity`;

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

ALTER TABLE `oms_payment_record`
    ADD COLUMN `refunded_amount` decimal(10,2) NOT NULL DEFAULT 0.00 AFTER `amount`;
