-- Persist the actual order discount, not the coupon face value, for every budget movement.
SET @coupon_discount_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'ums_member_coupon' AND column_name = 'actual_discount_amount');
SET @coupon_discount_sql = IF(@coupon_discount_exists = 0, 'ALTER TABLE ums_member_coupon ADD COLUMN actual_discount_amount decimal(10,2) DEFAULT NULL AFTER order_sn', 'SELECT 1');
PREPARE coupon_discount_statement FROM @coupon_discount_sql; EXECUTE coupon_discount_statement; DEALLOCATE PREPARE coupon_discount_statement;

SET @payment_transaction_index_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'oms_payment_record' AND index_name = 'uk_oms_payment_transaction_no');
UPDATE oms_payment_record payment
JOIN (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY transaction_no ORDER BY id) AS duplicate_no
    FROM oms_payment_record
    WHERE transaction_no IS NOT NULL AND transaction_no <> ''
) duplicate_transaction ON duplicate_transaction.id = payment.id
SET payment.transaction_no = CONCAT(LEFT(payment.transaction_no, 48), '-DUP-', payment.id)
WHERE duplicate_transaction.duplicate_no > 1;
SET @payment_transaction_index_sql = IF(@payment_transaction_index_exists = 0, 'ALTER TABLE oms_payment_record ADD UNIQUE KEY uk_oms_payment_transaction_no (transaction_no)', 'SELECT 1');
PREPARE payment_transaction_index_statement FROM @payment_transaction_index_sql; EXECUTE payment_transaction_index_statement; DEALLOCATE PREPARE payment_transaction_index_statement;
