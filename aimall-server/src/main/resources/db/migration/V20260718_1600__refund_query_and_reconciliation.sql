SET @qrc = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_refund_record' AND column_name='query_retry_count');
SET @sql = IF(@qrc=0, 'ALTER TABLE oms_refund_record ADD COLUMN query_retry_count int NOT NULL DEFAULT 0 AFTER manual_retry_count', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @ps = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_refund_record' AND column_name='provider_status');
SET @sql = IF(@ps=0, 'ALTER TABLE oms_refund_record ADD COLUMN provider_status varchar(32) DEFAULT NULL AFTER query_retry_count', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @lqt = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_refund_record' AND column_name='last_query_time');
SET @sql = IF(@lqt=0, 'ALTER TABLE oms_refund_record ADD COLUMN last_query_time datetime(6) DEFAULT NULL AFTER provider_status', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

CREATE TABLE IF NOT EXISTS payment_reconciliation_batch (
  id bigint NOT NULL AUTO_INCREMENT,
  batch_no varchar(64) NOT NULL,
  provider varchar(32) NOT NULL,
  reconcile_date date NOT NULL,
  status varchar(24) NOT NULL DEFAULT 'RUNNING',
  checked_count int NOT NULL DEFAULT 0,
  difference_count int NOT NULL DEFAULT 0,
  started_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  finished_at datetime(6) DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_reconcile_batch (provider,reconcile_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS payment_reconciliation_item (
  id bigint NOT NULL AUTO_INCREMENT,
  batch_id bigint NOT NULL,
  order_id bigint DEFAULT NULL,
  refund_record_id bigint DEFAULT NULL,
  difference_type varchar(48) NOT NULL,
  local_status varchar(32) DEFAULT NULL,
  provider_status varchar(32) DEFAULT NULL,
  local_amount decimal(18,4) DEFAULT NULL,
  provider_amount decimal(18,4) DEFAULT NULL,
  detail varchar(1000) DEFAULT NULL,
  resolution_status varchar(24) NOT NULL DEFAULT 'OPEN',
  created_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id), KEY idx_reconcile_batch (batch_id,resolution_status),
  KEY idx_reconcile_order (order_id), KEY idx_reconcile_refund (refund_record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
