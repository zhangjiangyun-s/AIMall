ALTER TABLE `oms_order`
    ADD COLUMN `inventory_reservation_status` varchar(20) NOT NULL DEFAULT 'NOT_RESERVED' AFTER `expire_time`,
    ADD KEY `idx_oms_order_inventory_status` (`inventory_reservation_status`);

-- Existing orders predate per-order reservation tracking. Do not infer ownership
-- from aggregate product lock_stock; only newly created orders become RESERVED.
