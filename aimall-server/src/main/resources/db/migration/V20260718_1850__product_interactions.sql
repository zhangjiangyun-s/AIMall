CREATE TABLE IF NOT EXISTS pms_product_review (
 id bigint NOT NULL AUTO_INCREMENT, member_id bigint NOT NULL, product_id bigint NOT NULL, order_item_id bigint NOT NULL,
 rating tinyint NOT NULL, content varchar(2000) NOT NULL DEFAULT '', status tinyint NOT NULL DEFAULT 1,
 create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(id), UNIQUE KEY uk_review_order_item(order_item_id),
 KEY idx_review_product(product_id,status,create_time), CONSTRAINT chk_review_rating CHECK(rating BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS ums_member_product_favorite (
 id bigint NOT NULL AUTO_INCREMENT, member_id bigint NOT NULL, product_id bigint NOT NULL, create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(id), UNIQUE KEY uk_favorite_member_product(member_id,product_id), KEY idx_favorite_member(member_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS ums_member_browse_history (
 id bigint NOT NULL AUTO_INCREMENT, member_id bigint NOT NULL, product_id bigint NOT NULL, view_count int NOT NULL DEFAULT 1,
 last_view_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(id), UNIQUE KEY uk_browse_member_product(member_id,product_id),
 KEY idx_browse_member_time(member_id,last_view_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
