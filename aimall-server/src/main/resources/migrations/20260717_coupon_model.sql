ALTER TABLE `sms_coupon`
    ADD COLUMN `total_quantity` int NOT NULL DEFAULT 0 AFTER `status`,
    ADD COLUMN `remaining_quantity` int NOT NULL DEFAULT 0 AFTER `total_quantity`,
    ADD COLUMN `per_member_limit` int NOT NULL DEFAULT 1 AFTER `remaining_quantity`,
    ADD COLUMN `receive_start_time` datetime DEFAULT NULL AFTER `per_member_limit`,
    ADD COLUMN `receive_end_time` datetime DEFAULT NULL AFTER `receive_start_time`,
    ADD COLUMN `scope_type` varchar(20) NOT NULL DEFAULT 'ALL' AFTER `receive_end_time`,
    ADD COLUMN `scope_ids` varchar(1000) DEFAULT NULL AFTER `scope_type`,
    ADD COLUMN `budget_amount` decimal(14,2) DEFAULT NULL AFTER `scope_ids`,
    ADD COLUMN `used_budget` decimal(14,2) NOT NULL DEFAULT 0.00 AFTER `budget_amount`,
    ADD COLUMN `refund_policy` varchar(20) NOT NULL DEFAULT 'RETURN' AFTER `used_budget`;

UPDATE `sms_coupon`
SET total_quantity = 1000000,
    remaining_quantity = 1000000
WHERE total_quantity = 0;

ALTER TABLE `ums_member_coupon`
    DROP INDEX `uk_member_coupon_member_coupon`,
    ADD COLUMN `claim_no` int NOT NULL DEFAULT 1 AFTER `coupon_name`,
    ADD UNIQUE KEY `uk_member_coupon_claim` (`member_id`, `coupon_id`, `claim_no`);
