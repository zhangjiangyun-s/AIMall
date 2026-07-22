INSERT IGNORE INTO ums_admin_permission (permission_code, permission_name, create_time) VALUES
('ADMIN_DIAGNOSTICS', '系统诊断', NOW()),
('PRODUCT_VIEW', '商品查看', NOW()),
('PRODUCT_EDIT', '商品编辑', NOW()),
('STOCK_ADJUST', '库存调整', NOW()),
('ORDER_VIEW', '订单查看', NOW()),
('ORDER_SHIP', '订单发货', NOW()),
('RETURN_VIEW', '售后查看', NOW()),
('RETURN_REVIEW', '售后审核', NOW()),
('RETURN_REFUND', '售后退款', NOW()),
('KNOWLEDGE_VIEW', '知识库查看', NOW()),
('KNOWLEDGE_EDIT', '知识库编辑', NOW()),
('KNOWLEDGE_PUBLISH', '知识库发布', NOW()),
('PAYMENT_VIEW', '支付查看', NOW()),
('PAYMENT_OPERATE', '支付恢复操作', NOW()),
('PAYMENT_RECONCILE', '支付对账', NOW());

INSERT IGNORE INTO ums_role_permission_relation (role_id, permission_id, create_time)
SELECT legacy.role_id, child.id, NOW()
FROM ums_role_permission_relation legacy
JOIN ums_admin_permission parent ON parent.id = legacy.permission_id
JOIN ums_admin_permission child ON
    (parent.permission_code = 'PRODUCT_MANAGE' AND child.permission_code IN ('PRODUCT_VIEW', 'PRODUCT_EDIT', 'STOCK_ADJUST'))
 OR (parent.permission_code = 'ORDER_MANAGE' AND child.permission_code IN ('ORDER_VIEW', 'ORDER_SHIP'))
 OR (parent.permission_code = 'RETURN_MANAGE' AND child.permission_code IN ('RETURN_VIEW', 'RETURN_REVIEW', 'RETURN_REFUND'))
 OR (parent.permission_code = 'KNOWLEDGE_MANAGE' AND child.permission_code IN ('KNOWLEDGE_VIEW', 'KNOWLEDGE_EDIT', 'KNOWLEDGE_PUBLISH'))
 OR (parent.permission_code = 'ADMIN_ACCESS' AND child.permission_code IN ('ADMIN_DIAGNOSTICS', 'PAYMENT_VIEW'));
