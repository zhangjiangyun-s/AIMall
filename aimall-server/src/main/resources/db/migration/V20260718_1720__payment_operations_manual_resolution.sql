SET @has_resolution_note = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'payment_reconciliation_item' AND column_name = 'resolution_note');
SET @add_resolution_note = IF(@has_resolution_note = 0, 'ALTER TABLE payment_reconciliation_item ADD COLUMN resolution_note varchar(1000) DEFAULT NULL AFTER resolution_status', 'SELECT 1');
PREPARE statement_to_run FROM @add_resolution_note; EXECUTE statement_to_run; DEALLOCATE PREPARE statement_to_run;

SET @has_resolved_by = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'payment_reconciliation_item' AND column_name = 'resolved_by');
SET @add_resolved_by = IF(@has_resolved_by = 0, 'ALTER TABLE payment_reconciliation_item ADD COLUMN resolved_by bigint DEFAULT NULL AFTER resolution_note', 'SELECT 1');
PREPARE statement_to_run FROM @add_resolved_by; EXECUTE statement_to_run; DEALLOCATE PREPARE statement_to_run;

SET @has_resolved_at = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'payment_reconciliation_item' AND column_name = 'resolved_at');
SET @add_resolved_at = IF(@has_resolved_at = 0, 'ALTER TABLE payment_reconciliation_item ADD COLUMN resolved_at datetime(6) DEFAULT NULL AFTER resolved_by', 'SELECT 1');
PREPARE statement_to_run FROM @add_resolved_at; EXECUTE statement_to_run; DEALLOCATE PREPARE statement_to_run;
