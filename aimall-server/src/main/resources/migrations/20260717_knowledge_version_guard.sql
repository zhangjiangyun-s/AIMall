-- Quarantine legacy chunks and rebuild tasks that were created without a document version.
UPDATE knowledge_chunk
SET status = 'DISABLED',
    embedding_sync_status = CASE
        WHEN embedding_sync_status = 'DELETED' THEN 'DELETED'
        WHEN embedding_id IS NULL THEN 'DELETED'
        ELSE 'DELETE_PENDING'
    END,
    updated_at = NOW()
WHERE doc_version_id IS NULL
  AND (
      status <> 'DISABLED'
      OR embedding_sync_status NOT IN ('DELETE_PENDING', 'DELETED')
  );

UPDATE knowledge_index_task
SET status = 'DEAD_LETTER',
    error_code = 'LEGACY_VERSION_MISSING',
    error_message = 'Legacy knowledge task has no doc_version_id and cannot enter the versioned pipeline',
    dead_letter_reason = 'Re-upload the source document through the versioned upload API',
    finished_at = NOW(),
    updated_at = NOW()
WHERE doc_version_id IS NULL
  AND task_type IN ('REBUILD_DOC', 'REBUILD_ALL')
  AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'FAILED', 'PARTIAL_FAILED');
