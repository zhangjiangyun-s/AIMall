USE aimall;

SET NAMES utf8mb4;

INSERT INTO `ums_admin_role` (`role_code`, `role_name`) VALUES ('SUPER_ADMIN', '超级管理员');
INSERT INTO `ums_admin_permission` (`permission_code`, `permission_name`) VALUES
('ADMIN_ACCESS', '后台访问'),
('PRODUCT_MANAGE', '商品管理'),
('ORDER_MANAGE', '订单管理'),
('RETURN_MANAGE', '售后管理'),
('KNOWLEDGE_MANAGE', '知识库管理'),
('AI_RECOVERY', 'AI 操作恢复'),
('ADMIN_DIAGNOSTICS', '系统诊断'),
('PRODUCT_VIEW', '商品查看'),
('PRODUCT_EDIT', '商品编辑'),
('STOCK_ADJUST', '库存调整'),
('ORDER_VIEW', '订单查看'),
('ORDER_SHIP', '订单发货'),
('RETURN_VIEW', '售后查看'),
('RETURN_REVIEW', '售后审核'),
('RETURN_REFUND', '售后退款'),
('KNOWLEDGE_VIEW', '知识库查看'),
('KNOWLEDGE_EDIT', '知识库编辑'),
('KNOWLEDGE_PUBLISH', '知识库发布'),
('PAYMENT_VIEW', '支付查看'),
('PAYMENT_OPERATE', '支付恢复操作'),
('PAYMENT_RECONCILE', '支付对账');

INSERT INTO `sms_coupon`
(`id`, `name`, `type`, `amount`, `min_point`, `platform`, `note`, `start_time`, `end_time`, `status`, `total_quantity`, `remaining_quantity`, `per_member_limit`) VALUES
(1, '满300减30', 'FULL_REDUCTION', 30.00, 300.00, 'ALL', '适用于全场普通商品', '2026-01-01 00:00:00', '2026-12-31 23:59:59', 1, 10000, 10000, 1),
(2, '满1000减120', 'FULL_REDUCTION', 120.00, 1000.00, 'ALL', '适用于数码与电脑商品', '2026-01-01 00:00:00', '2026-12-31 23:59:59', 1, 5000, 5000, 1),
(3, '新人券50元', 'FULL_REDUCTION', 50.00, 500.00, 'ALL', '新用户欢迎券', '2026-01-01 00:00:00', '2026-12-31 23:59:59', 1, 1000, 1000, 1);

INSERT INTO `pms_product_category`
(`id`, `parent_id`, `name`, `level`, `product_count`, `product_unit`, `nav_status`, `show_status`, `sort`, `icon`, `keywords`, `description`) VALUES
(1, 0, '数码电子', 0, 12, '件', 1, 1, 100, NULL, '数码,电子,智能设备', '商城主分类'),
(2, 1, '平板电脑', 1, 2, '台', 1, 1, 90, NULL, '平板,学习,办公', '适合学习和移动办公的平板设备'),
(3, 1, '笔记本电脑', 1, 2, '台', 1, 1, 80, NULL, '笔记本,轻薄本,办公本', '办公与学习场景的笔记本电脑'),
(4, 1, '耳机音频', 1, 3, '件', 1, 1, 70, NULL, '耳机,降噪,蓝牙', '无线与有线音频设备'),
(5, 1, '手机通讯', 1, 2, '台', 1, 1, 60, NULL, '手机,影像,旗舰', '智能手机与通讯设备'),
(6, 1, '智能穿戴', 1, 1, '件', 1, 1, 50, NULL, '手表,运动,健康', '智能手表与穿戴设备'),
(7, 1, '电脑配件', 1, 3, '件', 1, 1, 40, NULL, '键盘,鼠标,显示器', '桌面办公与电竞配件');

INSERT INTO `pms_product`
(`id`, `brand_id`, `category_id`, `name`, `pic`, `product_sn`, `delete_status`, `publish_status`, `new_status`, `recommand_status`, `verify_status`, `sort`, `sale`, `price`, `promotion_price`, `gift_point`, `sub_title`, `original_price`, `stock`, `low_stock`, `unit`, `weight`, `keywords`, `brand_name`, `product_category_name`, `description`, `detail_desc`, `detail_html`, `detail_mobile_html`, `create_time`) VALUES
(1001, 1, 2, '学习平板 A1', NULL, 'AIM-PAD-A1', 0, 1, 1, 1, 1, 100, 1260, 2999.00, 2799.00, 299, '护眼屏与手写笔，适合学习和轻办公', 3299.00, 88, 10, '台', 0.46, '学习平板,护眼,手写笔', 'AIMall', '平板电脑', '10.4 英寸护眼屏，8GB 内存，256GB 存储，支持手写笔。', '轻量便携，适合在线学习、批注和会议记录。', '<p>护眼屏、手写笔、长续航。</p>', '<p>护眼屏、手写笔、长续航。</p>', NOW()),
(1002, 1, 3, '轻薄笔记本 B2', NULL, 'AIM-NB-B2', 0, 1, 1, 1, 1, 99, 980, 3999.00, 3799.00, 399, '14 英寸轻薄办公本，长续航', 4299.00, 55, 8, '台', 1.30, '轻薄本,办公,长续航', 'AIMall', '笔记本电脑', '14 英寸全高清屏，16GB 内存，512GB 固态硬盘，续航约 10 小时，重量仅 1.3kg。', '轻薄机身与长续航，适合移动办公。', '<p>轻薄、长续航、高效办公。</p>', '<p>轻薄、长续航、高效办公。</p>', NOW()),
(1003, 2, 4, '无线蓝牙耳机 C3', NULL, 'AIM-AUDIO-C3', 0, 1, 0, 1, 1, 98, 2380, 399.00, 359.00, 39, '主动降噪真无线耳机', 499.00, 200, 20, '件', 0.05, '蓝牙耳机,降噪,真无线', 'SoundGo', '耳机音频', '主动降噪，真无线立体声，IPX5 防水，单次续航 8 小时。', '适合通勤、运动和会议使用。', '<p>降噪、防水、长续航。</p>', '<p>降噪、防水、长续航。</p>', NOW()),
(1004, 1, 3, '高性能游戏本 G1', NULL, 'AIM-GAME-G1', 0, 1, 1, 0, 1, 70, 420, 6999.00, NULL, 699, 'RTX 4060 高刷游戏本', 7599.00, 30, 5, '台', 2.10, '游戏本,高刷,RTX', 'AIMall', '笔记本电脑', '15.6 英寸高刷屏，RTX 4060 显卡，16GB 内存，1TB 固态硬盘。', '电竞级性能与散热表现。', '<p>高刷屏、独显、强散热。</p>', '<p>高刷屏、独显、强散热。</p>', NOW()),
(1005, 3, 5, '智能手机 X1', NULL, 'AIM-PHONE-X1', 0, 1, 1, 1, 1, 85, 1860, 4999.00, 4699.00, 499, '旗舰影像智能手机', 5299.00, 120, 15, '台', 0.19, '手机,旗舰,影像', 'Nexa', '手机通讯', '6.7 英寸 AMOLED 屏，5000 万像素三摄，12GB+256GB。', '高亮屏幕与旗舰影像体验。', '<p>旗舰屏幕、三摄、快充。</p>', '<p>旗舰屏幕、三摄、快充。</p>', NOW()),
(1006, 4, 6, '智能手表 W1', NULL, 'AIM-WATCH-W1', 0, 1, 0, 0, 1, 50, 600, 1299.00, NULL, 129, '运动健康智能手表', 1499.00, 60, 8, '件', 0.04, '手表,健康,GPS', 'FitPlus', '智能穿戴', '支持心率监测、GPS 运动记录与长续航。', '适合日常健康监测和运动记录。', '<p>健康监测、GPS、长续航。</p>', '<p>健康监测、GPS、长续航。</p>', NOW()),
(1007, 5, 7, '机械键盘 K1', NULL, 'AIM-KEY-K1', 0, 1, 0, 0, 1, 40, 1430, 499.00, NULL, 49, '87 键热插拔机械键盘', 599.00, 150, 20, '件', 0.85, '机械键盘,RGB,热插拔', 'KeyLab', '电脑配件', '87 键布局，RGB 背光，热插拔轴体，Type-C 连接。', '办公和游戏都很顺手。', '<p>热插拔、RGB、Type-C。</p>', '<p>热插拔、RGB、Type-C。</p>', NOW()),
(1008, 5, 7, '无线鼠标 M1', NULL, 'AIM-MOUSE-M1', 0, 1, 0, 0, 1, 30, 1760, 199.00, NULL, 19, '双模静音无线鼠标', 249.00, 180, 30, '件', 0.08, '鼠标,无线,静音', 'KeyLab', '电脑配件', '蓝牙与 2.4G 双模连接，静音按键，轻量设计。', '安静、轻便、续航持久。', '<p>双模、静音、轻量。</p>', '<p>双模、静音、轻量。</p>', NOW()),
(1009, 2, 4, '降噪头戴耳机 P1', NULL, 'AIM-AUDIO-P1', 0, 1, 0, 1, 1, 60, 730, 899.00, 799.00, 89, 'Hi-Res 长续航头戴耳机', 1099.00, 75, 10, '件', 0.26, '头戴耳机,降噪,Hi-Res', 'SoundGo', '耳机音频', 'Hi-Res 音质，自适应降噪，40 小时综合续航。', '适合沉浸听歌和办公会议。', '<p>Hi-Res、降噪、长续航。</p>', '<p>Hi-Res、降噪、长续航。</p>', NOW()),
(1010, 5, 7, '4K 显示器 U1', NULL, 'AIM-DISP-U1', 0, 1, 0, 0, 1, 20, 310, 2499.00, NULL, 249, '28 英寸 4K Type-C 显示器', 2799.00, 40, 5, '台', 5.60, '显示器,4K,Type-C', 'KeyLab', '电脑配件', '28 英寸 4K IPS 屏，HDR400，Type-C 65W 供电。', '一线连接笔记本，桌面更清爽。', '<p>4K、HDR、Type-C。</p>', '<p>4K、HDR、Type-C。</p>', NOW());

INSERT INTO `pms_sku_stock`
(`id`, `product_id`, `sku_code`, `price`, `stock`, `low_stock`, `pic`, `sale`, `promotion_price`, `lock_stock`, `sp_data`) VALUES
(2001, 1001, 'AIM-PAD-A1-8G256G-GRAY', 2999.00, 45, 5, NULL, 650, 2799.00, 0, '[{"key":"颜色","value":"深空灰"},{"key":"存储","value":"8G+256G"}]'),
(2002, 1001, 'AIM-PAD-A1-8G256G-SILVER', 2999.00, 43, 5, NULL, 610, 2799.00, 0, '[{"key":"颜色","value":"银色"},{"key":"存储","value":"8G+256G"}]'),
(2003, 1002, 'AIM-NB-B2-16G512G-GRAY', 3999.00, 30, 5, NULL, 520, 3799.00, 0, '[{"key":"颜色","value":"灰色"},{"key":"配置","value":"16G+512G"}]'),
(2004, 1002, 'AIM-NB-B2-16G512G-SILVER', 3999.00, 25, 5, NULL, 460, 3799.00, 0, '[{"key":"颜色","value":"银色"},{"key":"配置","value":"16G+512G"}]'),
(2005, 1003, 'AIM-AUDIO-C3-WHITE', 399.00, 100, 10, NULL, 1200, 359.00, 0, '[{"key":"颜色","value":"云白"}]'),
(2006, 1003, 'AIM-AUDIO-C3-BLACK', 399.00, 100, 10, NULL, 1180, 359.00, 0, '[{"key":"颜色","value":"曜黑"}]');

-- Knowledge documents are intentionally not seeded. Every document must enter through
-- the versioned upload, parsing, evaluation and publication workflow.
