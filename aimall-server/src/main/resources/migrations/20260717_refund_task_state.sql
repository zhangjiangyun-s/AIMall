ALTER TABLE `oms_refund_record`
    MODIFY COLUMN `refund_status` varchar(32) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN `handle_note` varchar(500) DEFAULT NULL AFTER `failure_reason`,
    ADD COLUMN `retry_count` int NOT NULL DEFAULT 0 AFTER `handle_note`,
    ADD COLUMN `max_retry` int NOT NULL DEFAULT 8 AFTER `retry_count`,
    ADD COLUMN `next_retry_time` datetime DEFAULT NULL AFTER `max_retry`,
    ADD COLUMN `channel_called_time` datetime DEFAULT NULL AFTER `next_retry_time`,
    ADD COLUMN `finished_time` datetime DEFAULT NULL AFTER `channel_called_time`;

UPDATE `oms_refund_record`
SET `refund_status` = CASE
    WHEN `refund_status` = 'PROCESSING' AND `refund_transaction_no` IS NOT NULL THEN 'CHANNEL_SUCCEEDED'
    WHEN `refund_status` = 'PROCESSING' THEN 'PENDING'
    ELSE `refund_status`
END;
