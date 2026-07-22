package com.aimall.server.config;

import cn.dev33.satoken.stp.StpUtil;
import com.aimall.server.entity.AdminOperationAudit;
import com.aimall.server.mapper.AdminOperationAuditMapper;
import com.aimall.server.common.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDateTime;

@Component
public class AdminOperationAuditInterceptor implements HandlerInterceptor {

    private static final String START_ATTRIBUTE = AdminOperationAuditInterceptor.class.getName() + ".start";
    private final AdminOperationAuditMapper auditMapper;
    private final ClientIpResolver clientIpResolver;

    public AdminOperationAuditInterceptor(AdminOperationAuditMapper auditMapper, ClientIpResolver clientIpResolver) {
        this.auditMapper = auditMapper;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_ATTRIBUTE, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception
    ) {
        AdminOperationAudit audit = new AdminOperationAudit();
        String loginId = StpUtil.isLogin() ? StpUtil.getLoginIdAsString() : null;
        if (loginId != null && loginId.startsWith("admin_")) {
            audit.setOperatorId(Long.parseLong(loginId.substring("admin_".length())));
            audit.setOperatorName(loginId);
        }
        audit.setHttpMethod(request.getMethod());
        audit.setRequestUri(limit(request.getRequestURI(), 500));
        audit.setClientIp(clientIpResolver.resolve(request));
        audit.setTraceId(limit(response.getHeader("X-Trace-Id"), 64));
        audit.setSuccess(exception == null && response.getStatus() < 400 ? 1 : 0);
        audit.setErrorMessage(exception == null
                ? (response.getStatus() >= 400 ? "HTTP " + response.getStatus() : null)
                : limit(exception.getMessage(), 500));
        Object started = request.getAttribute(START_ATTRIBUTE);
        audit.setDurationMs(started instanceof Long value ? (System.nanoTime() - value) / 1_000_000L : 0L);
        audit.setCreateTime(LocalDateTime.now());
        try {
            auditMapper.insert(audit);
        } catch (Exception ignored) {
            // Audit failure must be alerted by database/health monitoring without replacing the original response.
        }
    }

    private String limit(String value, int maxLength) {
        return value == null ? null : value.substring(0, Math.min(maxLength, value.length()));
    }
}
