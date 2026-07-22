package com.aimall.server.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AdminAuthorizationMapper {

    @Select("""
            SELECT COUNT(*)
            FROM ums_admin_role_relation relation
            JOIN ums_admin_role role ON role.id = relation.role_id AND role.status = 1
            LEFT JOIN ums_role_permission_relation role_permission ON role_permission.role_id = role.id
            LEFT JOIN ums_admin_permission permission ON permission.id = role_permission.permission_id
            WHERE relation.admin_id = #{adminId}
              AND (role.role_code = 'SUPER_ADMIN' OR permission.permission_code = #{permissionCode})
            """)
    int hasPermission(@Param("adminId") Long adminId, @Param("permissionCode") String permissionCode);

    @Insert("""
            INSERT IGNORE INTO ums_admin_role_relation (admin_id, role_id, create_time)
            SELECT #{adminId}, id, NOW() FROM ums_admin_role WHERE role_code = 'SUPER_ADMIN' AND status = 1
            """)
    int grantSuperAdmin(@Param("adminId") Long adminId);
}
