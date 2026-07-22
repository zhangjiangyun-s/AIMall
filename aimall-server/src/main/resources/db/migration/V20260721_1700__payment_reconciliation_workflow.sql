SET @legacy_query_retry_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_refund_record' AND column_name='query_retry_count');
SET @legacy_query_retry_sql=IF(@legacy_query_retry_exists=0,'ALTER TABLE oms_refund_record ADD COLUMN query_retry_count int NOT NULL DEFAULT 0 AFTER manual_retry_count','SELECT 1');
PREPARE legacy_query_retry_stmt FROM @legacy_query_retry_sql; EXECUTE legacy_query_retry_stmt; DEALLOCATE PREPARE legacy_query_retry_stmt;

SET @legacy_provider_status_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_refund_record' AND column_name='provider_status');
SET @legacy_provider_status_sql=IF(@legacy_provider_status_exists=0,'ALTER TABLE oms_refund_record ADD COLUMN provider_status varchar(32) NULL AFTER query_retry_count','SELECT 1');
PREPARE legacy_provider_status_stmt FROM @legacy_provider_status_sql; EXECUTE legacy_provider_status_stmt; DEALLOCATE PREPARE legacy_provider_status_stmt;

SET @legacy_last_query_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_refund_record' AND column_name='last_query_time');
SET @legacy_last_query_sql=IF(@legacy_last_query_exists=0,'ALTER TABLE oms_refund_record ADD COLUMN last_query_time datetime(6) NULL AFTER provider_status','SELECT 1');
PREPARE legacy_last_query_stmt FROM @legacy_last_query_sql; EXECUTE legacy_last_query_stmt; DEALLOCATE PREPARE legacy_last_query_stmt;

SET @order_hold_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_order' AND column_name='financial_hold');
SET @order_hold_sql=IF(@order_hold_exists=0,'ALTER TABLE oms_order ADD COLUMN financial_hold tinyint NOT NULL DEFAULT 0 AFTER inventory_reservation_status','SELECT 1');
PREPARE order_hold_stmt FROM @order_hold_sql; EXECUTE order_hold_stmt; DEALLOCATE PREPARE order_hold_stmt;

SET @order_hold_reason_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_order' AND column_name='financial_hold_reason');
SET @order_hold_reason_sql=IF(@order_hold_reason_exists=0,'ALTER TABLE oms_order ADD COLUMN financial_hold_reason varchar(255) NULL AFTER financial_hold','SELECT 1');
PREPARE order_hold_reason_stmt FROM @order_hold_reason_sql; EXECUTE order_hold_reason_stmt; DEALLOCATE PREPARE order_hold_reason_stmt;

SET @refund_state_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_refund_record' AND column_name='refund_state');
SET @refund_state_sql=IF(@refund_state_exists=0,'ALTER TABLE oms_refund_record ADD COLUMN refund_state varchar(32) NULL AFTER refund_status','SELECT 1');
PREPARE refund_state_stmt FROM @refund_state_sql; EXECUTE refund_state_stmt; DEALLOCATE PREPARE refund_state_stmt;

SET @channel_reference_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_refund_record' AND column_name='channel_reference');
SET @channel_reference_sql=IF(@channel_reference_exists=0,'ALTER TABLE oms_refund_record ADD COLUMN channel_reference varchar(128) NULL AFTER refund_transaction_no','SELECT 1');
PREPARE channel_reference_stmt FROM @channel_reference_sql; EXECUTE channel_reference_stmt; DEALLOCATE PREPARE channel_reference_stmt;

SET @last_query_at_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_refund_record' AND column_name='last_query_at');
SET @last_query_at_sql=IF(@last_query_at_exists=0,'ALTER TABLE oms_refund_record ADD COLUMN last_query_at datetime(6) NULL AFTER last_query_time','SELECT 1');
PREPARE last_query_at_stmt FROM @last_query_at_sql; EXECUTE last_query_at_stmt; DEALLOCATE PREPARE last_query_at_stmt;

SET @query_count_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_refund_record' AND column_name='query_count');
SET @query_count_sql=IF(@query_count_exists=0,'ALTER TABLE oms_refund_record ADD COLUMN query_count int NOT NULL DEFAULT 0 AFTER query_retry_count','SELECT 1');
PREPARE query_count_stmt FROM @query_count_sql; EXECUTE query_count_stmt; DEALLOCATE PREPARE query_count_stmt;

SET @refund_reconciliation_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_refund_record' AND column_name='reconciliation_status');
SET @refund_reconciliation_sql=IF(@refund_reconciliation_exists=0,'ALTER TABLE oms_refund_record ADD COLUMN reconciliation_status varchar(24) NOT NULL DEFAULT ''CLEAR'' AFTER provider_status','SELECT 1');
PREPARE refund_reconciliation_stmt FROM @refund_reconciliation_sql; EXECUTE refund_reconciliation_stmt; DEALLOCATE PREPARE refund_reconciliation_stmt;

SET @manual_owner_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_refund_record' AND column_name='manual_owner');
SET @manual_owner_sql=IF(@manual_owner_exists=0,'ALTER TABLE oms_refund_record ADD COLUMN manual_owner bigint NULL AFTER reconciliation_status','SELECT 1');
PREPARE manual_owner_stmt FROM @manual_owner_sql; EXECUTE manual_owner_stmt; DEALLOCATE PREPARE manual_owner_stmt;

UPDATE oms_refund_record
SET refund_state=refund_status,
    channel_reference=COALESCE(channel_reference,refund_transaction_no),
    last_query_at=COALESCE(last_query_at,last_query_time),
    query_count=GREATEST(query_count,query_retry_count)
WHERE refund_state IS NULL OR refund_state='' OR channel_reference IS NULL OR last_query_at IS NULL OR query_count<query_retry_count;
ALTER TABLE oms_refund_record MODIFY COLUMN refund_state varchar(32) NOT NULL DEFAULT 'PENDING';

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
  PRIMARY KEY (id),
  UNIQUE KEY uk_reconcile_batch (provider,reconcile_date)
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
  resolution_note varchar(1000) DEFAULT NULL,
  resolved_by bigint DEFAULT NULL,
  resolved_at datetime(6) DEFAULT NULL,
  created_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY idx_reconcile_batch (batch_id,resolution_status),
  KEY idx_reconcile_order (order_id),
  KEY idx_reconcile_refund (refund_record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @reconcile_auto_query_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='payment_reconciliation_item' AND column_name='auto_query_status');
SET @reconcile_auto_query_sql=IF(@reconcile_auto_query_exists=0,'ALTER TABLE payment_reconciliation_item ADD COLUMN auto_query_status varchar(24) NOT NULL DEFAULT ''PENDING'' AFTER resolution_status','SELECT 1');
PREPARE reconcile_auto_query_stmt FROM @reconcile_auto_query_sql; EXECUTE reconcile_auto_query_stmt; DEALLOCATE PREPARE reconcile_auto_query_stmt;

SET @reconcile_claimed_by_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='payment_reconciliation_item' AND column_name='claimed_by');
SET @reconcile_claimed_by_sql=IF(@reconcile_claimed_by_exists=0,'ALTER TABLE payment_reconciliation_item ADD COLUMN claimed_by bigint NULL AFTER auto_query_status','SELECT 1');
PREPARE reconcile_claimed_by_stmt FROM @reconcile_claimed_by_sql; EXECUTE reconcile_claimed_by_stmt; DEALLOCATE PREPARE reconcile_claimed_by_stmt;

SET @reconcile_claimed_at_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='payment_reconciliation_item' AND column_name='claimed_at');
SET @reconcile_claimed_at_sql=IF(@reconcile_claimed_at_exists=0,'ALTER TABLE payment_reconciliation_item ADD COLUMN claimed_at datetime(6) NULL AFTER claimed_by','SELECT 1');
PREPARE reconcile_claimed_at_stmt FROM @reconcile_claimed_at_sql; EXECUTE reconcile_claimed_at_stmt; DEALLOCATE PREPARE reconcile_claimed_at_stmt;

SET @reconcile_reviewed_by_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='payment_reconciliation_item' AND column_name='reviewed_by');
SET @reconcile_reviewed_by_sql=IF(@reconcile_reviewed_by_exists=0,'ALTER TABLE payment_reconciliation_item ADD COLUMN reviewed_by bigint NULL AFTER resolved_by','SELECT 1');
PREPARE reconcile_reviewed_by_stmt FROM @reconcile_reviewed_by_sql; EXECUTE reconcile_reviewed_by_stmt; DEALLOCATE PREPARE reconcile_reviewed_by_stmt;

SET @reconcile_reviewed_at_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='payment_reconciliation_item' AND column_name='reviewed_at');
SET @reconcile_reviewed_at_sql=IF(@reconcile_reviewed_at_exists=0,'ALTER TABLE payment_reconciliation_item ADD COLUMN reviewed_at datetime(6) NULL AFTER reviewed_by','SELECT 1');
PREPARE reconcile_reviewed_at_stmt FROM @reconcile_reviewed_at_sql; EXECUTE reconcile_reviewed_at_stmt; DEALLOCATE PREPARE reconcile_reviewed_at_stmt;

SET @reconcile_approval_no_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='payment_reconciliation_item' AND column_name='approval_no');
SET @reconcile_approval_no_sql=IF(@reconcile_approval_no_exists=0,'ALTER TABLE payment_reconciliation_item ADD COLUMN approval_no varchar(64) NULL AFTER reviewed_at','SELECT 1');
PREPARE reconcile_approval_no_stmt FROM @reconcile_approval_no_sql; EXECUTE reconcile_approval_no_stmt; DEALLOCATE PREPARE reconcile_approval_no_stmt;

CREATE TABLE IF NOT EXISTS payment_correction_event (
  id bigint NOT NULL AUTO_INCREMENT,
  event_no varchar(64) NOT NULL,
  reconciliation_item_id bigint NOT NULL,
  correction_type varchar(48) NOT NULL,
  reason varchar(1000) NOT NULL,
  evidence varchar(2000) NOT NULL,
  original_value_json text NOT NULL,
  proposed_value_json text NOT NULL,
  operator_id bigint NOT NULL,
  reviewer_id bigint DEFAULT NULL,
  approval_no varchar(64) DEFAULT NULL,
  status varchar(24) NOT NULL DEFAULT 'PENDING_REVIEW',
  review_note varchar(1000) DEFAULT NULL,
  created_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  reviewed_at datetime(6) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_payment_correction_event_no (event_no),
  KEY idx_payment_correction_item_status (reconciliation_item_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
