-- Stage 16: single-tenant event scope and durable async trace correlation.
SET @outbox_tenant_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='outbox_event' AND column_name='tenant_id');
SET @outbox_tenant_sql = IF(@outbox_tenant_exists=0, 'ALTER TABLE outbox_event ADD COLUMN tenant_id varchar(64) NOT NULL DEFAULT ''default'' AFTER trace_id', 'SELECT 1');
PREPARE outbox_tenant_stmt FROM @outbox_tenant_sql; EXECUTE outbox_tenant_stmt; DEALLOCATE PREPARE outbox_tenant_stmt;

SET @callback_trace_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='payment_callback_event' AND column_name='trace_id');
SET @callback_trace_sql = IF(@callback_trace_exists=0, 'ALTER TABLE payment_callback_event ADD COLUMN trace_id varchar(64) NULL AFTER request_id', 'SELECT 1');
PREPARE callback_trace_stmt FROM @callback_trace_sql; EXECUTE callback_trace_stmt; DEALLOCATE PREPARE callback_trace_stmt;

SET @callback_tenant_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='payment_callback_event' AND column_name='tenant_id');
SET @callback_tenant_sql = IF(@callback_tenant_exists=0, 'ALTER TABLE payment_callback_event ADD COLUMN tenant_id varchar(64) NOT NULL DEFAULT ''default'' AFTER trace_id', 'SELECT 1');
PREPARE callback_tenant_stmt FROM @callback_tenant_sql; EXECUTE callback_tenant_stmt; DEALLOCATE PREPARE callback_tenant_stmt;

SET @task_trace_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='knowledge_index_task' AND column_name='trace_id');
SET @task_trace_sql = IF(@task_trace_exists=0, 'ALTER TABLE knowledge_index_task ADD COLUMN trace_id varchar(64) NULL AFTER task_type', 'SELECT 1');
PREPARE task_trace_stmt FROM @task_trace_sql; EXECUTE task_trace_stmt; DEALLOCATE PREPARE task_trace_stmt;

UPDATE payment_callback_event SET trace_id=COALESCE(NULLIF(trace_id,''), CONCAT('legacy-callback-', id)) WHERE trace_id IS NULL OR trace_id='';
UPDATE knowledge_index_task SET trace_id=COALESCE(NULLIF(trace_id,''), CONCAT('legacy-task-', id)) WHERE trace_id IS NULL OR trace_id='';

ALTER TABLE payment_callback_event MODIFY COLUMN trace_id varchar(64) NOT NULL;
ALTER TABLE knowledge_index_task MODIFY COLUMN trace_id varchar(64) NOT NULL;

SET @outbox_tenant_index_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='outbox_event' AND index_name='idx_outbox_tenant_claim');
SET @outbox_tenant_index_sql = IF(@outbox_tenant_index_exists=0, 'CREATE INDEX idx_outbox_tenant_claim ON outbox_event(tenant_id,status,next_attempt_at,id)', 'SELECT 1');
PREPARE outbox_tenant_index_stmt FROM @outbox_tenant_index_sql; EXECUTE outbox_tenant_index_stmt; DEALLOCATE PREPARE outbox_tenant_index_stmt;
