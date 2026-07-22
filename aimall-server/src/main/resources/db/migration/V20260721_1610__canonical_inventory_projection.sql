CREATE OR REPLACE VIEW inventory_balance_projection AS
SELECT 'SKU' AS inventory_type,
       sku.id AS inventory_id,
       sku.product_id,
       GREATEST(COALESCE(sku.stock,0),0) AS on_hand,
       GREATEST(COALESCE(sku.lock_stock,0),0) AS reserved,
       GREATEST(COALESCE(sku.sale,0),0) AS sold,
       GREATEST(COALESCE(sku.stock,0)-COALESCE(sku.lock_stock,0),0) AS available,
       CASE WHEN sku.status=1 AND product.publish_status=1 AND product.delete_status=0 THEN 1 ELSE 0 END AS enabled
FROM pms_sku_stock sku
JOIN pms_product product ON product.id=sku.product_id
UNION ALL
SELECT 'SPU' AS inventory_type,
       product.id AS inventory_id,
       product.id AS product_id,
       GREATEST(COALESCE(product.stock,0),0) AS on_hand,
       GREATEST(COALESCE(product.lock_stock,0),0) AS reserved,
       GREATEST(COALESCE(product.sale,0),0) AS sold,
       GREATEST(COALESCE(product.stock,0)-COALESCE(product.lock_stock,0),0) AS available,
       CASE WHEN product.publish_status=1 AND product.delete_status=0 THEN 1 ELSE 0 END AS enabled
FROM pms_product product
WHERE NOT EXISTS (SELECT 1 FROM pms_sku_stock sku WHERE sku.product_id=product.id);
