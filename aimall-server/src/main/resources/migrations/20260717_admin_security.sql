ALTER TABLE `ums_admin`
    ADD COLUMN `mfa_secret` varchar(128) DEFAULT NULL AFTER `status`,
    ADD COLUMN `mfa_enabled` tinyint NOT NULL DEFAULT 0 AFTER `mfa_secret`;

CREATE TABLE `ums_admin_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role_code` varchar(64) NOT NULL,
  `role_name` varchar(100) NOT NULL,
  `status` tinyint NOT NULL DEFAULT 1,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `ums_admin_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `permission_code` varchar(100) NOT NULL,
  `permission_name` varchar(100) NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_permission_code` (`permission_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `ums_admin_role_relation` (
  `admin_id` bigint NOT NULL,
  `role_id` bigint NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`admin_id`, `role_id`),
  KEY `idx_admin_role_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `ums_role_permission_relation` (
  `role_id` bigint NOT NULL,
  `permission_id` bigint NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`role_id`, `permission_id`),
  KEY `idx_role_permission_permission` (`permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `ums_admin_login_attempt` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(64) NOT NULL,
  `client_ip` varchar(64) NOT NULL,
  `success` tinyint NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_admin_login_attempt_guard` (`username`, `client_ip`, `success`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `ums_admin_role` (`role_code`, `role_name`) VALUES ('SUPER_ADMIN', '超级管理员');
INSERT INTO `ums_admin_permission` (`permission_code`, `permission_name`) VALUES
('ADMIN_ACCESS', '后台访问'),
('PRODUCT_MANAGE', '商品管理'),
('ORDER_MANAGE', '订单管理'),
('RETURN_MANAGE', '售后管理'),
('KNOWLEDGE_MANAGE', '知识库管理'),
('AI_RECOVERY', 'AI 操作恢复');

INSERT IGNORE INTO `ums_admin_role_relation` (`admin_id`, `role_id`)
SELECT admin.id, role.id FROM ums_admin admin JOIN ums_admin_role role ON role.role_code = 'SUPER_ADMIN';
