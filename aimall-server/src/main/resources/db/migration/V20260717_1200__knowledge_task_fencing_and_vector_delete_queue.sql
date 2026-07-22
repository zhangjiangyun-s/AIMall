-- Fence knowledge processing attempts, atomically enforce one active task per document
-- version, and turn vector cleanup into a leased retry queue.

-- Each DDL is independently guarded: MySQL autocommits DDL, so a stopped
-- deployment must be able to resume after only a subset of columns exists.
SET @execution_token_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'knowledge_index_task' AND column_name = 'execution_token');
SET @execution_token_sql = IF(@execution_token_exists = 0, 'ALTER TABLE knowledge_index_task ADD COLUMN execution_token varchar(64) DEFAULT NULL AFTER status', 'SELECT 1');
PREPARE execution_token_statement FROM @execution_token_sql; EXECUTE execution_token_statement; DEALLOCATE PREPARE execution_token_statement;

SET @attempt_no_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'knowledge_index_task' AND column_name = 'attempt_no');
SET @attempt_no_sql = IF(@attempt_no_exists = 0, 'ALTER TABLE knowledge_index_task ADD COLUMN attempt_no int NOT NULL DEFAULT 0 AFTER execution_token', 'SELECT 1');
PREPARE attempt_no_statement FROM @attempt_no_sql; EXECUTE attempt_no_statement; DEALLOCATE PREPARE attempt_no_statement;

SET @vector_delete_retry_count_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'knowledge_chunk' AND column_name = 'vector_delete_retry_count');
SET @vector_delete_retry_count_sql = IF(@vector_delete_retry_count_exists = 0, 'ALTER TABLE knowledge_chunk ADD COLUMN vector_delete_retry_count int NOT NULL DEFAULT 0 AFTER embedding_sync_status', 'SELECT 1');
PREPARE vector_delete_retry_count_statement FROM @vector_delete_retry_count_sql; EXECUTE vector_delete_retry_count_statement; DEALLOCATE PREPARE vector_delete_retry_count_statement;
SET @vector_delete_next_retry_at_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'knowledge_chunk' AND column_name = 'vector_delete_next_retry_at');
SET @vector_delete_next_retry_at_sql = IF(@vector_delete_next_retry_at_exists = 0, 'ALTER TABLE knowledge_chunk ADD COLUMN vector_delete_next_retry_at datetime DEFAULT NULL AFTER vector_delete_retry_count', 'SELECT 1');
PREPARE vector_delete_next_retry_at_statement FROM @vector_delete_next_retry_at_sql; EXECUTE vector_delete_next_retry_at_statement; DEALLOCATE PREPARE vector_delete_next_retry_at_statement;
SET @vector_delete_claim_token_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'knowledge_chunk' AND column_name = 'vector_delete_claim_token');
SET @vector_delete_claim_token_sql = IF(@vector_delete_claim_token_exists = 0, 'ALTER TABLE knowledge_chunk ADD COLUMN vector_delete_claim_token varchar(64) DEFAULT NULL AFTER vector_delete_next_retry_at', 'SELECT 1');
PREPARE vector_delete_claim_token_statement FROM @vector_delete_claim_token_sql; EXECUTE vector_delete_claim_token_statement; DEALLOCATE PREPARE vector_delete_claim_token_statement;
SET @vector_delete_claim_until_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'knowledge_chunk' AND column_name = 'vector_delete_claim_until');
SET @vector_delete_claim_until_sql = IF(@vector_delete_claim_until_exists = 0, 'ALTER TABLE knowledge_chunk ADD COLUMN vector_delete_claim_until datetime DEFAULT NULL AFTER vector_delete_claim_token', 'SELECT 1');
PREPARE vector_delete_claim_until_statement FROM @vector_delete_claim_until_sql; EXECUTE vector_delete_claim_until_statement; DEALLOCATE PREPARE vector_delete_claim_until_statement;
SET @vector_delete_last_error_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'knowledge_chunk' AND column_name = 'vector_delete_last_error');
SET @vector_delete_last_error_sql = IF(@vector_delete_last_error_exists = 0, 'ALTER TABLE knowledge_chunk ADD COLUMN vector_delete_last_error varchar(1000) DEFAULT NULL AFTER vector_delete_claim_until', 'SELECT 1');
PREPARE vector_delete_last_error_statement FROM @vector_delete_last_error_sql; EXECUTE vector_delete_last_error_statement; DEALLOCATE PREPARE vector_delete_last_error_statement;

UPDATE knowledge_index_task task
JOIN (
    SELECT id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY doc_version_id, task_type
                   ORDER BY
                       CASE
                           WHEN status = 'RUNNING' AND lock_until > NOW() THEN 0
                           WHEN status = 'DISPATCHING' AND timeout_at > NOW() THEN 1
                           WHEN status = 'PENDING' AND timeout_at > NOW() THEN 2
                           WHEN status = 'RETRY_WAIT' AND next_retry_at > NOW() THEN 3
                           ELSE 9
                       END,
                       COALESCE(last_heartbeat_at, updated_at, created_at) DESC,
                       id ASC
               ) AS row_no
        FROM knowledge_index_task
        WHERE task_type = 'PROCESS_DOC_UPLOAD'
          AND doc_version_id IS NOT NULL
          AND status IN ('PENDING', 'DISPATCHING', 'RUNNING', 'RETRY_WAIT')
    ) ranked
    WHERE row_no > 1
) duplicate_task ON duplicate_task.id = task.id
SET task.status = 'DEAD_LETTER',
    task.error_code = 'DUPLICATE_ACTIVE_TASK',
    task.error_message = 'Duplicate active task quarantined before unique-key migration',
    task.dead_letter_reason = 'Retry the surviving task or create a new task after it reaches a terminal state',
    task.finished_at = NOW(),
    task.updated_at = NOW();

SET @active_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'knowledge_index_task'
      AND column_name = 'active_doc_version_id'
);
SET @active_column_sql = IF(
    @active_column_exists = 0,
    'ALTER TABLE knowledge_index_task ADD COLUMN active_doc_version_id bigint GENERATED ALWAYS AS (CASE WHEN task_type = ''PROCESS_DOC_UPLOAD'' AND status IN (''PENDING'', ''DISPATCHING'', ''RUNNING'', ''RETRY_WAIT'') THEN doc_version_id ELSE NULL END) STORED',
    'SELECT 1'
);
PREPARE active_column_statement FROM @active_column_sql;
EXECUTE active_column_statement;
DEALLOCATE PREPARE active_column_statement;

SET @active_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'knowledge_index_task'
      AND index_name = 'uk_knowledge_index_task_active_version'
);
SET @active_index_sql = IF(
    @active_index_exists = 0,
    'ALTER TABLE knowledge_index_task ADD UNIQUE KEY uk_knowledge_index_task_active_version (active_doc_version_id)',
    'SELECT 1'
);
PREPARE active_index_statement FROM @active_index_sql;
EXECUTE active_index_statement;
DEALLOCATE PREPARE active_index_statement;

SET @delete_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'knowledge_chunk'
      AND index_name = 'idx_knowledge_chunk_vector_delete'
);
SET @delete_index_sql = IF(
    @delete_index_exists = 0,
    'ALTER TABLE knowledge_chunk ADD KEY idx_knowledge_chunk_vector_delete (embedding_sync_status, vector_delete_next_retry_at, id)',
    'SELECT 1'
);
PREPARE delete_index_statement FROM @delete_index_sql;
EXECUTE delete_index_statement;
DEALLOCATE PREPARE delete_index_statement;

UPDATE knowledge_chunk
SET status = 'DISABLED',
    embedding_sync_status = CASE
        WHEN embedding_sync_status = 'DELETED' THEN 'DELETED'
        WHEN embedding_id IS NULL THEN 'DELETED'
        ELSE 'DELETE_PENDING'
    END,
    vector_delete_next_retry_at = NULL,
    updated_at = NOW()
WHERE doc_version_id IS NULL
  AND (
      status <> 'DISABLED'
      OR embedding_sync_status NOT IN ('DELETE_PENDING', 'DELETED')
  );
