-- Stage 25: restore the canonical outbox lifecycle and preserve dead-letter evidence.
SET @payload_hash_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='outbox_event' AND column_name='payload_hash');
SET @sql = IF(@payload_hash_exists=0,
  'ALTER TABLE outbox_event ADD COLUMN payload_hash char(64) NULL AFTER payload_json', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @last_owner_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='outbox_event' AND column_name='last_lease_owner');
SET @sql = IF(@last_owner_exists=0,
  'ALTER TABLE outbox_event ADD COLUMN last_lease_owner varchar(128) NULL AFTER lease_until', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

UPDATE outbox_event
SET payload_hash=COALESCE(NULLIF(payload_hash,''), SHA2(CAST(payload_json AS CHAR CHARACTER SET utf8mb4),256)),
    status=CASE status
      WHEN 'NEW' THEN 'PENDING'
      WHEN 'RETRY' THEN 'RETRY_WAIT'
      WHEN 'SENT' THEN 'SUCCEEDED'
      ELSE status
    END;

ALTER TABLE outbox_event
  MODIFY COLUMN payload_hash char(64) NOT NULL,
  MODIFY COLUMN status varchar(24) NOT NULL DEFAULT 'PENDING';
