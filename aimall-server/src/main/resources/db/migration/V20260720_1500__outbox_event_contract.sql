-- Stage 15: versioned event envelope and canonical lifecycle names.
SET @occurred_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'outbox_event' AND column_name = 'occurred_at_utc');
SET @occurred_sql = IF(@occurred_exists = 0, 'ALTER TABLE outbox_event ADD COLUMN occurred_at_utc datetime(6) NULL AFTER trace_id', 'SELECT 1');
PREPARE occurred_statement FROM @occurred_sql; EXECUTE occurred_statement; DEALLOCATE PREPARE occurred_statement;

SET @producer_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'outbox_event' AND column_name = 'producer_version');
SET @producer_sql = IF(@producer_exists = 0, 'ALTER TABLE outbox_event ADD COLUMN producer_version varchar(64) NULL AFTER occurred_at_utc', 'SELECT 1');
PREPARE producer_statement FROM @producer_sql; EXECUTE producer_statement; DEALLOCATE PREPARE producer_statement;

SET @schema_version_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'outbox_event' AND column_name = 'payload_schema_version');
SET @schema_version_sql = IF(@schema_version_exists = 0, 'ALTER TABLE outbox_event ADD COLUMN payload_schema_version int NULL AFTER producer_version', 'SELECT 1');
PREPARE schema_version_statement FROM @schema_version_sql; EXECUTE schema_version_statement; DEALLOCATE PREPARE schema_version_statement;

UPDATE outbox_event
SET occurred_at_utc = COALESCE(occurred_at_utc, created_at),
    producer_version = COALESCE(NULLIF(producer_version, ''), 'aimall-server/legacy'),
    payload_schema_version = COALESCE(payload_schema_version, 1),
    status = CASE status
        WHEN 'PENDING' THEN 'NEW'
        WHEN 'SUCCEEDED' THEN 'SENT'
        WHEN 'RETRY_WAIT' THEN 'RETRY'
        ELSE status
    END;

ALTER TABLE outbox_event
    MODIFY COLUMN occurred_at_utc datetime(6) NOT NULL,
    MODIFY COLUMN producer_version varchar(64) NOT NULL,
    MODIFY COLUMN payload_schema_version int NOT NULL;
