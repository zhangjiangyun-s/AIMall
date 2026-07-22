package com.aimall.server.service;

import com.aimall.server.mapper.AdminAuthorizationMapper;
import org.springframework.stereotype.Service;
import com.aimall.server.exception.ForbiddenException;

@Service
public class AdminAuthorizationService {

    private final AdminAuthorizationMapper authorizationMapper;

    public AdminAuthorizationService(AdminAuthorizationMapper authorizationMapper) {
        this.authorizationMapper = authorizationMapper;
    }

    public void check(Long adminId, String permissionCode) {
        if (authorizationMapper.hasPermission(adminId, permissionCode) <= 0) {
            throw new ForbiddenException("管理员缺少权限：" + permissionCode);
        }
    }

    public void grantBootstrapAdmin(Long adminId) {
        authorizationMapper.grantSuperAdmin(adminId);
    }
}
