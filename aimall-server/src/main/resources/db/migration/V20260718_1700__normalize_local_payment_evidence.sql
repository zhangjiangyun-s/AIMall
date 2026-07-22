SET @has_event_source = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'payment_callback_event' AND column_name = 'event_source');
SET @add_event_source = IF(@has_event_source = 0, 'ALTER TABLE payment_callback_event ADD COLUMN event_source varchar(32) NOT NULL DEFAULT ''ASYNC_NOTIFY'' AFTER request_id', 'SELECT 1');
PREPARE statement_to_run FROM @add_event_source; EXECUTE statement_to_run; DEALLOCATE PREPARE statement_to_run;

SET @has_order_id = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'payment_callback_event' AND column_name = 'order_id');
SET @add_order_id = IF(@has_order_id = 0, 'ALTER TABLE payment_callback_event ADD COLUMN order_id bigint DEFAULT NULL AFTER event_source', 'SELECT 1');
PREPARE statement_to_run FROM @add_order_id; EXECUTE statement_to_run; DEALLOCATE PREPARE statement_to_run;

SET @has_provider_status = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'payment_callback_event' AND column_name = 'provider_status');
SET @add_provider_status = IF(@has_provider_status = 0, 'ALTER TABLE payment_callback_event ADD COLUMN provider_status varchar(32) DEFAULT NULL AFTER order_id', 'SELECT 1');
PREPARE statement_to_run FROM @add_provider_status; EXECUTE statement_to_run; DEALLOCATE PREPARE statement_to_run;

SET @has_amount = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'payment_callback_event' AND column_name = 'amount');
SET @add_amount = IF(@has_amount = 0, 'ALTER TABLE payment_callback_event ADD COLUMN amount decimal(18,4) DEFAULT NULL AFTER provider_status', 'SELECT 1');
PREPARE statement_to_run FROM @add_amount; EXECUTE statement_to_run; DEALLOCATE PREPARE statement_to_run;

UPDATE payment_callback_event evidence
JOIN oms_payment_record payment ON payment.order_sn = evidence.request_id
SET evidence.event_source = COALESCE(evidence.event_source, 'ASYNC_NOTIFY'),
    evidence.order_id = payment.order_id,
    evidence.provider_status = CASE
        WHEN payment.pay_status IN ('PAID', 'PARTIALLY_REFUNDED', 'REFUNDED') THEN 'TRADE_SUCCESS'
        ELSE payment.payment_state
    END,
    evidence.amount = CASE
        WHEN payment.paid_amount > 0 THEN payment.paid_amount
        ELSE payment.amount
    END
WHERE evidence.provider = 'ALIPAY_SANDBOX'
  AND (evidence.order_id IS NULL OR evidence.provider_status IS NULL OR evidence.amount IS NULL);

SET @has_evidence_index = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'payment_callback_event' AND index_name = 'idx_payment_evidence_order');
SET @add_evidence_index = IF(@has_evidence_index = 0, 'ALTER TABLE payment_callback_event ADD KEY idx_payment_evidence_order (provider, order_id, signature_valid, provider_status)', 'SELECT 1');
PREPARE statement_to_run FROM @add_evidence_index; EXECUTE statement_to_run; DEALLOCATE PREPARE statement_to_run;
