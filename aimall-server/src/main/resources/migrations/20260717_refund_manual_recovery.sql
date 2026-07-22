ALTER TABLE `oms_refund_record`
    ADD COLUMN `manual_retry_count` int NOT NULL DEFAULT 0 AFTER `max_retry`,
    ADD COLUMN `closed_time` datetime DEFAULT NULL AFTER `finished_time`,
    ADD COLUMN `closed_reason` varchar(500) DEFAULT NULL AFTER `closed_time`;
