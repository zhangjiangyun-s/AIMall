SET @usage_state_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ums_member_coupon' AND column_name='usage_state');
SET @usage_state_sql=IF(@usage_state_exists=0,'ALTER TABLE ums_member_coupon ADD COLUMN usage_state varchar(24) NULL AFTER status','SELECT 1');
PREPARE usage_state_stmt FROM @usage_state_sql; EXECUTE usage_state_stmt; DEALLOCATE PREPARE usage_state_stmt;

SET @locked_order_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ums_member_coupon' AND column_name='locked_order_id');
SET @locked_order_sql=IF(@locked_order_exists=0,'ALTER TABLE ums_member_coupon ADD COLUMN locked_order_id bigint NULL AFTER usage_state','SELECT 1');
PREPARE locked_order_stmt FROM @locked_order_sql; EXECUTE locked_order_stmt; DEALLOCATE PREPARE locked_order_stmt;

SET @locked_at_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ums_member_coupon' AND column_name='locked_at');
SET @locked_at_sql=IF(@locked_at_exists=0,'ALTER TABLE ums_member_coupon ADD COLUMN locked_at datetime(6) NULL AFTER locked_order_id','SELECT 1');
PREPARE locked_at_stmt FROM @locked_at_sql; EXECUTE locked_at_stmt; DEALLOCATE PREPARE locked_at_stmt;

SET @consumed_at_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ums_member_coupon' AND column_name='consumed_at');
SET @consumed_at_sql=IF(@consumed_at_exists=0,'ALTER TABLE ums_member_coupon ADD COLUMN consumed_at datetime(6) NULL AFTER locked_at','SELECT 1');
PREPARE consumed_at_stmt FROM @consumed_at_sql; EXECUTE consumed_at_stmt; DEALLOCATE PREPARE consumed_at_stmt;

SET @released_at_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ums_member_coupon' AND column_name='released_at');
SET @released_at_sql=IF(@released_at_exists=0,'ALTER TABLE ums_member_coupon ADD COLUMN released_at datetime(6) NULL AFTER consumed_at','SELECT 1');
PREPARE released_at_stmt FROM @released_at_sql; EXECUTE released_at_stmt; DEALLOCATE PREPARE released_at_stmt;

UPDATE ums_member_coupon SET usage_state=CASE status WHEN 1 THEN 'CONSUMED' WHEN 2 THEN 'VOID' ELSE 'AVAILABLE' END WHERE usage_state IS NULL OR usage_state='';
UPDATE ums_member_coupon SET locked_order_id=order_id, consumed_at=COALESCE(consumed_at,used_time) WHERE usage_state='CONSUMED';
ALTER TABLE ums_member_coupon MODIFY COLUMN usage_state varchar(24) NOT NULL DEFAULT 'AVAILABLE';

SET @usage_index_exists=(SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='ums_member_coupon' AND index_name='idx_member_coupon_usage_state');
SET @usage_index_sql=IF(@usage_index_exists=0,'CREATE INDEX idx_member_coupon_usage_state ON ums_member_coupon(member_id,usage_state,coupon_id)','SELECT 1');
PREPARE usage_index_stmt FROM @usage_index_sql; EXECUTE usage_index_stmt; DEALLOCATE PREPARE usage_index_stmt;
