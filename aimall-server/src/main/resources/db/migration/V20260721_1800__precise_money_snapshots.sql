CREATE TABLE IF NOT EXISTS money_precision_assessment (
  id bigint NOT NULL AUTO_INCREMENT, table_name varchar(64) NOT NULL, column_name varchar(64) NOT NULL,
  max_abs_amount decimal(30,4) NOT NULL DEFAULT 0, source_scale int NOT NULL,
  observed_currency varchar(3) NOT NULL DEFAULT 'CNY', assessed_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(id), UNIQUE KEY uk_money_assessment(table_name,column_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO money_precision_assessment(table_name,column_name,max_abs_amount,source_scale,observed_currency)
SELECT 'oms_order','pay_amount',COALESCE(MAX(ABS(pay_amount)),0),COALESCE((SELECT numeric_scale FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_order' AND column_name='pay_amount'),0),'CNY' FROM oms_order
ON DUPLICATE KEY UPDATE max_abs_amount=VALUES(max_abs_amount),source_scale=VALUES(source_scale),observed_currency=VALUES(observed_currency),assessed_at=NOW(6);
INSERT INTO money_precision_assessment(table_name,column_name,max_abs_amount,source_scale,observed_currency)
SELECT 'oms_payment_record','amount',COALESCE(MAX(ABS(amount)),0),COALESCE((SELECT numeric_scale FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_payment_record' AND column_name='amount'),0),'CNY' FROM oms_payment_record
ON DUPLICATE KEY UPDATE max_abs_amount=VALUES(max_abs_amount),source_scale=VALUES(source_scale),observed_currency=VALUES(observed_currency),assessed_at=NOW(6);
INSERT INTO money_precision_assessment(table_name,column_name,max_abs_amount,source_scale,observed_currency)
SELECT 'oms_refund_record','amount',COALESCE(MAX(ABS(amount)),0),COALESCE((SELECT numeric_scale FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_refund_record' AND column_name='amount'),0),'CNY' FROM oms_refund_record
ON DUPLICATE KEY UPDATE max_abs_amount=VALUES(max_abs_amount),source_scale=VALUES(source_scale),observed_currency=VALUES(observed_currency),assessed_at=NOW(6);
INSERT INTO money_precision_assessment(table_name,column_name,max_abs_amount,source_scale,observed_currency)
SELECT 'pms_product','price',COALESCE(MAX(ABS(price)),0),COALESCE((SELECT numeric_scale FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pms_product' AND column_name='price'),0),'CNY' FROM pms_product
ON DUPLICATE KEY UPDATE max_abs_amount=VALUES(max_abs_amount),source_scale=VALUES(source_scale),observed_currency=VALUES(observed_currency),assessed_at=NOW(6);
INSERT INTO money_precision_assessment(table_name,column_name,max_abs_amount,source_scale,observed_currency)
SELECT 'pms_product','promotion_price',COALESCE(MAX(ABS(promotion_price)),0),COALESCE((SELECT numeric_scale FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pms_product' AND column_name='promotion_price'),0),'CNY' FROM pms_product
ON DUPLICATE KEY UPDATE max_abs_amount=VALUES(max_abs_amount),source_scale=VALUES(source_scale),observed_currency=VALUES(observed_currency),assessed_at=NOW(6);
INSERT INTO money_precision_assessment(table_name,column_name,max_abs_amount,source_scale,observed_currency)
SELECT 'pms_product','original_price',COALESCE(MAX(ABS(original_price)),0),COALESCE((SELECT numeric_scale FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pms_product' AND column_name='original_price'),0),'CNY' FROM pms_product
ON DUPLICATE KEY UPDATE max_abs_amount=VALUES(max_abs_amount),source_scale=VALUES(source_scale),observed_currency=VALUES(observed_currency),assessed_at=NOW(6);
INSERT INTO money_precision_assessment(table_name,column_name,max_abs_amount,source_scale,observed_currency)
SELECT 'pms_sku_stock','price',COALESCE(MAX(ABS(price)),0),COALESCE((SELECT numeric_scale FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pms_sku_stock' AND column_name='price'),0),'CNY' FROM pms_sku_stock
ON DUPLICATE KEY UPDATE max_abs_amount=VALUES(max_abs_amount),source_scale=VALUES(source_scale),observed_currency=VALUES(observed_currency),assessed_at=NOW(6);
INSERT INTO money_precision_assessment(table_name,column_name,max_abs_amount,source_scale,observed_currency)
SELECT 'pms_sku_stock','promotion_price',COALESCE(MAX(ABS(promotion_price)),0),COALESCE((SELECT numeric_scale FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pms_sku_stock' AND column_name='promotion_price'),0),'CNY' FROM pms_sku_stock
ON DUPLICATE KEY UPDATE max_abs_amount=VALUES(max_abs_amount),source_scale=VALUES(source_scale),observed_currency=VALUES(observed_currency),assessed_at=NOW(6);
INSERT INTO money_precision_assessment(table_name,column_name,max_abs_amount,source_scale,observed_currency)
SELECT 'pms_product_price_rule','price',COALESCE(MAX(ABS(price)),0),COALESCE((SELECT numeric_scale FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pms_product_price_rule' AND column_name='price'),0),'CNY' FROM pms_product_price_rule
ON DUPLICATE KEY UPDATE max_abs_amount=VALUES(max_abs_amount),source_scale=VALUES(source_scale),observed_currency=VALUES(observed_currency),assessed_at=NOW(6);

SET @order_currency=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_order' AND column_name='currency_code');
SET @sql=IF(@order_currency=0,'ALTER TABLE oms_order ADD COLUMN currency_code varchar(3) NOT NULL DEFAULT ''CNY''','SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @order_scale=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_order' AND column_name='currency_scale');
SET @sql=IF(@order_scale=0,'ALTER TABLE oms_order ADD COLUMN currency_scale tinyint NOT NULL DEFAULT 2','SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @payment_currency=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_payment_record' AND column_name='currency_code');
SET @sql=IF(@payment_currency=0,'ALTER TABLE oms_payment_record ADD COLUMN currency_code varchar(3) NOT NULL DEFAULT ''CNY''','SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @payment_scale=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_payment_record' AND column_name='currency_scale');
SET @sql=IF(@payment_scale=0,'ALTER TABLE oms_payment_record ADD COLUMN currency_scale tinyint NOT NULL DEFAULT 2','SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @payment_paid=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_payment_record' AND column_name='paid_amount');
SET @sql=IF(@payment_paid=0,'ALTER TABLE oms_payment_record ADD COLUMN paid_amount decimal(18,4) NOT NULL DEFAULT 0.0000','SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @payment_refunded=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_payment_record' AND column_name='refunded_amount');
SET @sql=IF(@payment_refunded=0,'ALTER TABLE oms_payment_record ADD COLUMN refunded_amount decimal(18,4) NOT NULL DEFAULT 0.0000','SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @refund_currency=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_refund_record' AND column_name='currency_code');
SET @sql=IF(@refund_currency=0,'ALTER TABLE oms_refund_record ADD COLUMN currency_code varchar(3) NOT NULL DEFAULT ''CNY''','SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @refund_scale=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='oms_refund_record' AND column_name='currency_scale');
SET @sql=IF(@refund_scale=0,'ALTER TABLE oms_refund_record ADD COLUMN currency_scale tinyint NOT NULL DEFAULT 2','SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @inventory_ledger_exists=(SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='inventory_ledger');
SET @inventory_available_delta=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='inventory_ledger' AND column_name='available_delta');
SET @sql=IF(@inventory_ledger_exists=1 AND @inventory_available_delta=0,'ALTER TABLE inventory_ledger ADD COLUMN available_delta int NOT NULL DEFAULT 0 AFTER sold_delta','SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @product_price=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pms_product' AND column_name='price_v2');
SET @sql=IF(@product_price=0,'ALTER TABLE pms_product ADD COLUMN price_v2 decimal(18,4) NULL','SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @product_promotion=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pms_product' AND column_name='promotion_price_v2');
SET @sql=IF(@product_promotion=0,'ALTER TABLE pms_product ADD COLUMN promotion_price_v2 decimal(18,4) NULL','SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @product_original=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pms_product' AND column_name='original_price_v2');
SET @sql=IF(@product_original=0,'ALTER TABLE pms_product ADD COLUMN original_price_v2 decimal(18,4) NULL','SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sku_price=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pms_sku_stock' AND column_name='price_v2');
SET @sql=IF(@sku_price=0,'ALTER TABLE pms_sku_stock ADD COLUMN price_v2 decimal(18,4) NULL','SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sku_promotion=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pms_sku_stock' AND column_name='promotion_price_v2');
SET @sql=IF(@sku_promotion=0,'ALTER TABLE pms_sku_stock ADD COLUMN promotion_price_v2 decimal(18,4) NULL','SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @rule_price=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pms_product_price_rule' AND column_name='price_v2');
SET @sql=IF(@rule_price=0,'ALTER TABLE pms_product_price_rule ADD COLUMN price_v2 decimal(18,4) NULL','SELECT 1'); PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

UPDATE pms_product SET price_v2=COALESCE(price_v2,price),promotion_price_v2=COALESCE(promotion_price_v2,promotion_price),original_price_v2=COALESCE(original_price_v2,original_price);
UPDATE pms_sku_stock SET price_v2=COALESCE(price_v2,price),promotion_price_v2=COALESCE(promotion_price_v2,promotion_price);
UPDATE pms_product_price_rule SET price_v2=COALESCE(price_v2,price);

CREATE TABLE IF NOT EXISTS order_money_snapshot_v2 (
 order_id bigint NOT NULL,currency_code varchar(3) NOT NULL,currency_scale tinyint NOT NULL,
 total_amount decimal(18,4) NOT NULL,pay_amount decimal(18,4) NOT NULL,freight_amount decimal(18,4) NOT NULL,
 promotion_amount decimal(18,4) NOT NULL,coupon_amount decimal(18,4) NOT NULL,discount_amount decimal(18,4) NOT NULL,
 refunded_amount decimal(18,4) NOT NULL DEFAULT 0,updated_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),PRIMARY KEY(order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS order_item_money_snapshot_v2 (
 order_item_id bigint NOT NULL,order_id bigint NOT NULL,product_price decimal(18,4) NOT NULL,promotion_amount decimal(18,4) NOT NULL,
 coupon_amount decimal(18,4) NOT NULL,real_amount decimal(18,4) NOT NULL,updated_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(order_item_id),KEY idx_money_item_order(order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS payment_money_snapshot_v2 (
 payment_id bigint NOT NULL,currency_code varchar(3) NOT NULL,currency_scale tinyint NOT NULL,amount decimal(18,4) NOT NULL,
 paid_amount decimal(18,4) NOT NULL,refunded_amount decimal(18,4) NOT NULL,updated_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),PRIMARY KEY(payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS refund_money_snapshot_v2 (
 refund_record_id bigint NOT NULL,currency_code varchar(3) NOT NULL,currency_scale tinyint NOT NULL,amount decimal(18,4) NOT NULL,
 updated_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),PRIMARY KEY(refund_record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS return_item_money_snapshot_v2 (
 return_item_id bigint NOT NULL,order_item_id bigint NOT NULL,refund_amount decimal(18,4) NOT NULL,
 updated_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),PRIMARY KEY(return_item_id),KEY idx_money_return_order_item(order_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO order_money_snapshot_v2 SELECT id,currency_code,currency_scale,total_amount,pay_amount,freight_amount,promotion_amount,coupon_amount,discount_amount,refunded_amount,NOW(6) FROM oms_order;
INSERT IGNORE INTO order_item_money_snapshot_v2 SELECT id,order_id,product_price,promotion_amount,coupon_amount,real_amount,NOW(6) FROM oms_order_item;
INSERT IGNORE INTO payment_money_snapshot_v2 SELECT id,currency_code,currency_scale,amount,paid_amount,refunded_amount,NOW(6) FROM oms_payment_record;
INSERT IGNORE INTO refund_money_snapshot_v2 SELECT id,currency_code,currency_scale,amount,NOW(6) FROM oms_refund_record;
INSERT IGNORE INTO return_item_money_snapshot_v2 SELECT id,order_item_id,refund_amount,NOW(6) FROM oms_order_return_item;
