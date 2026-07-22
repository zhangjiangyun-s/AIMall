CREATE TABLE IF NOT EXISTS pms_brand (
  id bigint NOT NULL AUTO_INCREMENT, name varchar(100) NOT NULL, logo varchar(500) DEFAULT NULL,
  description varchar(500) DEFAULT NULL, sort int NOT NULL DEFAULT 0, status tinyint NOT NULL DEFAULT 1,
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id), UNIQUE KEY uk_pms_brand_name (name), KEY idx_pms_brand_status (status, sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS pms_category_attribute_template (
  id bigint NOT NULL AUTO_INCREMENT, category_id bigint NOT NULL, template_name varchar(100) NOT NULL,
  schema_json json NOT NULL, status tinyint NOT NULL DEFAULT 1,
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  active_identity tinyint GENERATED ALWAYS AS (CASE WHEN status = 1 THEN 1 ELSE NULL END) STORED,
  PRIMARY KEY (id), UNIQUE KEY uk_category_active_template (category_id, active_identity),
  KEY idx_attribute_template_category (category_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
