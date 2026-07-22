SET @has_member_level = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ums_member' AND column_name = 'member_level'
);
SET @add_member_level = IF(
    @has_member_level = 0,
    'ALTER TABLE ums_member ADD COLUMN member_level varchar(32) NOT NULL DEFAULT ''NORMAL'' AFTER phone',
    'SELECT 1'
);
PREPARE statement_to_run FROM @add_member_level;
EXECUTE statement_to_run;
DEALLOCATE PREPARE statement_to_run;

CREATE TABLE IF NOT EXISTS pms_product_price_rule (
  id bigint NOT NULL AUTO_INCREMENT,
  product_id bigint NOT NULL,
  sku_id bigint DEFAULT NULL,
  rule_type varchar(20) NOT NULL,
  rule_name varchar(100) NOT NULL,
  member_level varchar(32) DEFAULT NULL,
  price decimal(10,2) NOT NULL,
  per_member_limit int DEFAULT NULL,
  priority int NOT NULL DEFAULT 0,
  status tinyint NOT NULL DEFAULT 1,
  start_time datetime NOT NULL,
  end_time datetime NOT NULL,
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_price_rule_lookup (product_id, sku_id, status, start_time, end_time),
  KEY idx_price_rule_member (member_level, status, start_time, end_time),
  CONSTRAINT chk_price_rule_type CHECK (rule_type IN ('MEMBER', 'ACTIVITY')),
  CONSTRAINT chk_price_rule_price CHECK (price >= 0),
  CONSTRAINT chk_price_rule_limit CHECK (per_member_limit IS NULL OR per_member_limit >= 0),
  CONSTRAINT chk_price_rule_window CHECK (end_time > start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
