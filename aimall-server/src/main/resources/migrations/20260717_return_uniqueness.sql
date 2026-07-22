CREATE TEMPORARY TABLE `tmp_return_keep` AS
SELECT MIN(id) AS keep_id, order_id, type
FROM oms_order_return_apply
WHERE status IN (0, 1, 5)
GROUP BY order_id, type
HAVING COUNT(*) > 1;

UPDATE oms_order_return_apply ra
JOIN tmp_return_keep keep_row
  ON keep_row.order_id = ra.order_id
 AND keep_row.type = ra.type
SET ra.status = 4,
    ra.handle_note = '迁移清理：关闭重复活动售后申请',
    ra.handle_time = NOW(),
    ra.update_time = NOW()
WHERE ra.id <> keep_row.keep_id
  AND ra.status IN (0, 1, 5);

DROP TEMPORARY TABLE `tmp_return_keep`;

ALTER TABLE `oms_order_return_apply`
    ADD COLUMN `active_identity` tinyint GENERATED ALWAYS AS (CASE WHEN `status` IN (0, 1, 5) THEN 1 ELSE NULL END) STORED,
    ADD UNIQUE KEY `uk_return_active_order_type` (`order_id`, `type`, `active_identity`);
