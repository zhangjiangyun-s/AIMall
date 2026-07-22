-- Stage 11 Agentic RAG acceptance fixtures.
-- Test accounts are registered through /api/user/register before this script runs.

START TRANSACTION;

SET @user_a := (SELECT id FROM ums_member WHERE username = 'phase11_user_a' LIMIT 1);
SET @user_b := (SELECT id FROM ums_member WHERE username = 'phase11_user_b' LIMIT 1);

UPDATE ums_member SET nickname = '阶段11测试用户A' WHERE id = @user_a;
UPDATE ums_member SET nickname = '阶段11测试用户B' WHERE id = @user_b;

INSERT INTO oms_order (
  member_id, member_username, order_sn, total_amount, pay_amount, freight_amount,
  status, delivery_company, delivery_sn, receiver_name, receiver_phone,
  receiver_province, receiver_city, receiver_region, receiver_detail_address,
  note, create_time, payment_time, delivery_time, receive_time
)
SELECT @user_a, 'phase11_user_a', fixture.order_sn, 4299.00, 4299.00, 0.00,
       fixture.status, fixture.delivery_company, fixture.delivery_sn, '测试用户A', '13800000001',
       '测试省', '测试市', '测试区', '阶段11验收专用地址',
       'PHASE11_ACCEPTANCE_FIXTURE', fixture.create_time, fixture.payment_time,
       fixture.delivery_time, fixture.receive_time
FROM (
  SELECT 'AIM20260714000000000001' order_sn, 0 status, NULL delivery_company, NULL delivery_sn,
         NOW() create_time, NULL payment_time, NULL delivery_time, NULL receive_time
  UNION ALL
  SELECT 'AIM20260714000000000002', 1, NULL, NULL,
         DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), NULL, NULL
  UNION ALL
  SELECT 'AIM20260714000000000003', 2, '顺丰速运', 'SF-PHASE11-0003',
         DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), NULL
  UNION ALL
  SELECT 'AIM20260714000000000004', 3, '顺丰速运', 'SF-PHASE11-0004',
         DATE_SUB(NOW(), INTERVAL 10 DAY), DATE_SUB(NOW(), INTERVAL 10 DAY), DATE_SUB(NOW(), INTERVAL 9 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY)
  UNION ALL
  SELECT 'AIM20260714000000000005', 4, NULL, NULL,
         DATE_SUB(NOW(), INTERVAL 5 DAY), NULL, NULL, NULL
) fixture
WHERE NOT EXISTS (SELECT 1 FROM oms_order existing WHERE existing.order_sn = fixture.order_sn);

INSERT INTO oms_order (
  member_id, member_username, order_sn, total_amount, pay_amount, freight_amount,
  status, receiver_name, receiver_phone, receiver_province, receiver_city,
  receiver_region, receiver_detail_address, note, create_time, payment_time
)
SELECT @user_b, 'phase11_user_b', 'AIM20260714000000000006', 2999.00, 2999.00, 0.00,
       1, '测试用户B', '13800000002', '测试省', '测试市', '测试区',
       '阶段11越权验收专用地址', 'PHASE11_PERMISSION_FIXTURE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM oms_order WHERE order_sn = 'AIM20260714000000000006');

INSERT INTO oms_order_item (
  order_id, order_sn, product_id, product_name, product_brand, product_sn,
  product_price, product_quantity, product_category_id, real_amount, product_attr
)
SELECT orders.id, orders.order_sn, 1103, '轻羽 Air 14 轻薄本', 'AIM', 'P1103',
       4299.00, 1, 3, 4299.00, '16GB+512GB'
FROM oms_order orders
WHERE orders.order_sn BETWEEN 'AIM20260714000000000001' AND 'AIM20260714000000000005'
  AND NOT EXISTS (SELECT 1 FROM oms_order_item item WHERE item.order_id = orders.id);

INSERT INTO oms_order_item (
  order_id, order_sn, product_id, product_name, product_brand, product_sn,
  product_price, product_quantity, product_category_id, real_amount, product_attr
)
SELECT orders.id, orders.order_sn, 1001, '学习平板 A1', 'AIM', 'P1001',
       2999.00, 1, 2, 2999.00, '8GB+256GB'
FROM oms_order orders
WHERE orders.order_sn = 'AIM20260714000000000006'
  AND NOT EXISTS (SELECT 1 FROM oms_order_item item WHERE item.order_id = orders.id);

INSERT INTO oms_order_return_apply (
  order_id, order_sn, member_id, status, type, reason, description,
  return_amount, handle_note, handle_time, create_time
)
SELECT orders.id, orders.order_sn, @user_a, 1, 'RETURN_REFUND', '商品不符合预期',
       '阶段11售后与政策联合问答验收', 4299.00, '审核通过，等待用户寄回商品', NOW(), NOW()
FROM oms_order orders
WHERE orders.order_sn = 'AIM20260714000000000004'
  AND NOT EXISTS (
    SELECT 1 FROM oms_order_return_apply returns
    WHERE returns.order_id = orders.id AND returns.description = '阶段11售后与政策联合问答验收'
  );

COMMIT;

SELECT id, order_sn, member_id, status FROM oms_order
WHERE order_sn BETWEEN 'AIM20260714000000000001' AND 'AIM20260714000000000006'
ORDER BY order_sn;

SELECT id, order_id, order_sn, member_id, status, reason
FROM oms_order_return_apply
WHERE description = '阶段11售后与政策联合问答验收';
