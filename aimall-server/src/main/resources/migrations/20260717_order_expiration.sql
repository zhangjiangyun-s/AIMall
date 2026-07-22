ALTER TABLE `oms_order`
    ADD COLUMN `expire_time` datetime DEFAULT NULL AFTER `modify_time`,
    ADD KEY `idx_oms_order_expiration` (`status`, `expire_time`);

UPDATE `oms_order`
SET `expire_time` = DATE_ADD(`create_time`, INTERVAL 30 MINUTE)
WHERE `status` = 0 AND `expire_time` IS NULL;
