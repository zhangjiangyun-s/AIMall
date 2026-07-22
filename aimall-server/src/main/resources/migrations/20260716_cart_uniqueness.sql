CREATE TEMPORARY TABLE `tmp_cart_merge` AS
SELECT MIN(id) AS keep_id,
       member_id,
       product_id,
       COALESCE(product_sku_id, 0) AS sku_identity,
       LEAST(99, SUM(quantity)) AS merged_quantity
FROM oms_cart_item
WHERE delete_status = 0
GROUP BY member_id, product_id, COALESCE(product_sku_id, 0)
HAVING COUNT(*) > 1;

UPDATE oms_cart_item item
JOIN tmp_cart_merge merged ON merged.keep_id = item.id
SET item.quantity = merged.merged_quantity,
    item.modify_date = NOW();

UPDATE oms_cart_item item
JOIN tmp_cart_merge merged
  ON merged.member_id = item.member_id
 AND merged.product_id = item.product_id
 AND merged.sku_identity = COALESCE(item.product_sku_id, 0)
SET item.delete_status = 1,
    item.modify_date = NOW()
WHERE item.id <> merged.keep_id
  AND item.delete_status = 0;

DROP TEMPORARY TABLE `tmp_cart_merge`;

ALTER TABLE `oms_cart_item`
    ADD COLUMN `sku_identity` bigint GENERATED ALWAYS AS (COALESCE(`product_sku_id`, 0)) STORED,
    ADD COLUMN `active_identity` tinyint GENERATED ALWAYS AS (CASE WHEN `delete_status` = 0 THEN 1 ELSE NULL END) STORED;

ALTER TABLE `oms_cart_item`
    ADD UNIQUE KEY `uk_cart_active_item` (`member_id`, `product_id`, `sku_identity`, `active_identity`);
