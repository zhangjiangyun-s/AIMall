package com.aimall.server.config;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import com.aimall.server.service.AdminAuthorizationService;
import com.aimall.server.security.RequireAdminPermission;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import com.aimall.server.exception.ForbiddenException;
import com.aimall.server.exception.UnauthorizedException;
import com.aimall.server.exception.ConflictException;

@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final AdminAuthorizationService authorizationService;

    public AdminAuthInterceptor(AdminAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!StpUtil.isLogin()) {
            throw new UnauthorizedException("请先登录管理员账号");
        }
        String loginId = StpUtil.getLoginIdAsString();
        if (loginId == null || !loginId.startsWith("admin_")) {
            throw new ForbiddenException("无管理员权限");
        }
        Long adminId = Long.parseLong(loginId.substring("admin_".length()));
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequireAdminPermission permission = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(), RequireAdminPermission.class
        );
        if (permission == null) {
            permission = AnnotatedElementUtils.findMergedAnnotation(
                    handlerMethod.getBeanType(), RequireAdminPermission.class
            );
        }
        if (permission == null || permission.value().isBlank()) {
            throw new ConflictException("ADMIN_PERMISSION_METADATA_MISSING", "管理员接口未声明权限元数据");
        }
        authorizationService.check(adminId, permission.value());
        return true;
    }
}
