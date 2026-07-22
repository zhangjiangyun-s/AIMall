CREATE TABLE IF NOT EXISTS pms_price_history (
  id bigint NOT NULL AUTO_INCREMENT,
  target_type varchar(16) NOT NULL,
  product_id bigint NOT NULL,
  sku_id bigint DEFAULT NULL,
  price_type varchar(24) NOT NULL,
  old_amount decimal(18,4) DEFAULT NULL,
  new_amount decimal(18,4) DEFAULT NULL,
  operator_id bigint DEFAULT NULL,
  change_reason varchar(255) NOT NULL,
  create_time datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY idx_price_history_product (product_id, create_time),
  KEY idx_price_history_sku (sku_id, create_time),
  KEY idx_price_history_operator (operator_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
