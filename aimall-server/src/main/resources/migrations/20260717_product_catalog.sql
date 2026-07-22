ALTER TABLE `pms_sku_stock`
    ADD COLUMN `status` tinyint NOT NULL DEFAULT 1 AFTER `sp_data`;

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
