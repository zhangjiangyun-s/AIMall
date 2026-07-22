-- P0 payment foundation. External providers are not called by this migration.
-- All tables live in the same MySQL database as orders so local facts and outbox
-- rows commit atomically.
SET @payment_state_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'oms_payment_record' AND column_name = 'payment_state');
SET @payment_state_sql = IF(@payment_state_exists = 0, 'ALTER TABLE oms_payment_record ADD COLUMN payment_state varchar(32) NOT NULL DEFAULT ''WAITING_PAYMENT'' AFTER pay_status', 'SELECT 1');
PREPARE payment_state_statement FROM @payment_state_sql; EXECUTE payment_state_statement; DEALLOCATE PREPARE payment_state_statement;

SET @paid_amount_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'oms_payment_record' AND column_name = 'paid_amount');
SET @paid_amount_sql = IF(@paid_amount_exists = 0, 'ALTER TABLE oms_payment_record ADD COLUMN paid_amount decimal(18,4) NOT NULL DEFAULT 0.0000 AFTER amount', 'SELECT 1');
PREPARE paid_amount_statement FROM @paid_amount_sql; EXECUTE paid_amount_statement; DEALLOCATE PREPARE paid_amount_statement;

SET @provider_reference_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'oms_payment_record' AND column_name = 'provider_reference');
SET @provider_reference_sql = IF(@provider_reference_exists = 0, 'ALTER TABLE oms_payment_record ADD COLUMN provider_reference varchar(128) DEFAULT NULL AFTER transaction_no', 'SELECT 1');
PREPARE provider_reference_statement FROM @provider_reference_sql; EXECUTE provider_reference_statement; DEALLOCATE PREPARE provider_reference_statement;

CREATE TABLE IF NOT EXISTS payment_attempt (
  id bigint NOT NULL AUTO_INCREMENT,
  attempt_id varchar(64) NOT NULL,
  order_id bigint NOT NULL,
  payment_record_id bigint NOT NULL,
  member_id bigint NOT NULL,
  provider varchar(32) NOT NULL,
  request_id varchar(128) NOT NULL,
  amount decimal(18,4) NOT NULL,
  currency varchar(8) NOT NULL,
  state varchar(32) NOT NULL DEFAULT 'INIT',
  provider_reference varchar(128) DEFAULT NULL,
  failure_code varchar(64) DEFAULT NULL,
  failure_message varchar(500) DEFAULT NULL,
  last_query_at datetime(6) DEFAULT NULL,
  expires_at datetime(6) DEFAULT NULL,
  create_time datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  update_time datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_payment_attempt_id (attempt_id),
  UNIQUE KEY uk_payment_attempt_request (provider, request_id),
  KEY idx_payment_attempt_order (order_id, state),
  KEY idx_payment_attempt_query (state, last_query_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS payment_callback_event (
  id bigint NOT NULL AUTO_INCREMENT,
  event_id varchar(128) NOT NULL,
  provider varchar(32) NOT NULL,
  provider_reference varchar(128) DEFAULT NULL,
  request_id varchar(128) DEFAULT NULL,
  signature_valid tinyint NOT NULL DEFAULT 0,
  payload_hash char(64) NOT NULL,
  raw_payload mediumtext NOT NULL,
  processing_state varchar(24) NOT NULL DEFAULT 'RECEIVED',
  processed_at datetime(6) DEFAULT NULL,
  failure_code varchar(64) DEFAULT NULL,
  create_time datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_payment_callback_event (provider, event_id),
  KEY idx_payment_callback_reference (provider, provider_reference),
  KEY idx_payment_callback_state (processing_state, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS outbox_event (
  id bigint NOT NULL AUTO_INCREMENT,
  event_id varchar(64) NOT NULL,
  aggregate_type varchar(64) NOT NULL,
  aggregate_id varchar(64) NOT NULL,
  aggregate_version bigint NOT NULL DEFAULT 0,
  event_type varchar(100) NOT NULL,
  idempotency_key varchar(128) NOT NULL,
  payload_json json NOT NULL,
  trace_id varchar(64) NOT NULL,
  status varchar(24) NOT NULL DEFAULT 'PENDING',
  retry_count int NOT NULL DEFAULT 0,
  next_attempt_at datetime(6) DEFAULT NULL,
  lease_owner varchar(128) DEFAULT NULL,
  lease_until datetime(6) DEFAULT NULL,
  last_error_code varchar(64) DEFAULT NULL,
  last_error_message varchar(1000) DEFAULT NULL,
  created_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  sent_at datetime(6) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_outbox_event_id (event_id),
  UNIQUE KEY uk_outbox_idempotency (idempotency_key),
  KEY idx_outbox_claim (status, next_attempt_at, lease_until, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
