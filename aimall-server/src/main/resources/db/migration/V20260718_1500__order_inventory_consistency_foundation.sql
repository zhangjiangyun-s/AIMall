-- P0-4 foundation: durable transition and inventory audit facts.
CREATE TABLE IF NOT EXISTS order_transition_log (
  id bigint NOT NULL AUTO_INCREMENT,
  order_id bigint NOT NULL,
  from_status int NOT NULL,
  to_status int NOT NULL,
  actor_type varchar(32) NOT NULL,
  actor_id varchar(64) DEFAULT NULL,
  idempotency_key varchar(128) NOT NULL,
  trace_id varchar(64) DEFAULT NULL,
  reason varchar(500) DEFAULT NULL,
  create_time datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_transition_idempotency (idempotency_key),
  KEY idx_order_transition_order (order_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS inventory_ledger (
  id bigint NOT NULL AUTO_INCREMENT,
  event_id varchar(128) NOT NULL,
  product_id bigint NOT NULL,
  sku_id bigint DEFAULT NULL,
  order_id bigint DEFAULT NULL,
  order_item_id bigint DEFAULT NULL,
  operation varchar(32) NOT NULL,
  quantity int NOT NULL,
  on_hand_delta int NOT NULL DEFAULT 0,
  reserved_delta int NOT NULL DEFAULT 0,
  sold_delta int NOT NULL DEFAULT 0,
  before_available int DEFAULT NULL,
  after_available int DEFAULT NULL,
  create_time datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_inventory_ledger_event (event_id),
  KEY idx_inventory_ledger_item (product_id, sku_id, create_time),
  KEY idx_inventory_ledger_order (order_id, order_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
