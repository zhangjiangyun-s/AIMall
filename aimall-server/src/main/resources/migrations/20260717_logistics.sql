ALTER TABLE `oms_order_item`
    ADD COLUMN `shipped_quantity` int NOT NULL DEFAULT 0 AFTER `refunded_quantity`;

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
