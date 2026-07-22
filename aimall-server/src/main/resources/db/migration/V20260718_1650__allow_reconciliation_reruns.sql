SET @has_old_unique = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'payment_reconciliation_batch'
      AND index_name = 'uk_reconcile_batch'
);
SET @drop_old_unique = IF(
    @has_old_unique > 0,
    'ALTER TABLE payment_reconciliation_batch DROP INDEX uk_reconcile_batch',
    'SELECT 1'
);
PREPARE statement_to_run FROM @drop_old_unique;
EXECUTE statement_to_run;
DEALLOCATE PREPARE statement_to_run;

SET @has_active_identity = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'payment_reconciliation_batch'
      AND column_name = 'active_identity'
);
SET @add_active_identity = IF(
    @has_active_identity = 0,
    'ALTER TABLE payment_reconciliation_batch ADD COLUMN active_identity TINYINT GENERATED ALWAYS AS (CASE WHEN status = ''RUNNING'' THEN 1 ELSE NULL END) STORED',
    'SELECT 1'
);
PREPARE statement_to_run FROM @add_active_identity;
EXECUTE statement_to_run;
DEALLOCATE PREPARE statement_to_run;

SET @has_active_unique = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'payment_reconciliation_batch'
      AND index_name = 'uk_reconcile_active_batch'
);
SET @add_active_unique = IF(
    @has_active_unique = 0,
    'ALTER TABLE payment_reconciliation_batch ADD UNIQUE KEY uk_reconcile_active_batch (provider, reconcile_date, active_identity)',
    'SELECT 1'
);
PREPARE statement_to_run FROM @add_active_unique;
EXECUTE statement_to_run;
DEALLOCATE PREPARE statement_to_run;

SET @has_history_index = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'payment_reconciliation_batch'
      AND index_name = 'idx_reconcile_history'
);
SET @add_history_index = IF(
    @has_history_index = 0,
    'ALTER TABLE payment_reconciliation_batch ADD KEY idx_reconcile_history (provider, reconcile_date, id)',
    'SELECT 1'
);
PREPARE statement_to_run FROM @add_history_index;
EXECUTE statement_to_run;
DEALLOCATE PREPARE statement_to_run;
