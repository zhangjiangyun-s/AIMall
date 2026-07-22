package com.aimall.server.tenant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
public class TenantPolicy {
    public static final String SINGLE_TENANT = "SINGLE_TENANT";
    public static final String MULTI_TENANT = "MULTI_TENANT";

    private final String mode;
    private final String defaultTenantId;

    public TenantPolicy(
            @Value("${aimall.tenant.mode:SINGLE_TENANT}") String mode,
            @Value("${aimall.tenant.default-id:default}") String defaultTenantId
    ) {
        this.mode = normalizeMode(mode);
        this.defaultTenantId = requireId(defaultTenantId);
        if (!"default".equals(this.defaultTenantId)) {
            throw new IllegalStateException("当前版本的 SINGLE_TENANT 默认 tenantId 必须为 default");
        }
        if (MULTI_TENANT.equals(this.mode)) {
            throw new IllegalStateException("MULTI_TENANT 尚未完成数据库强制约束，当前版本拒绝启动");
        }
    }

    public String resolve(String requestedTenantId) {
        String tenantId = requestedTenantId == null || requestedTenantId.isBlank()
                ? defaultTenantId : requireId(requestedTenantId);
        if (SINGLE_TENANT.equals(mode) && !defaultTenantId.equals(tenantId)) {
            throw new IllegalArgumentException("当前部署为单租户模式，拒绝非默认 tenantId");
        }
        return tenantId;
    }

    public String mode() {
        return mode;
    }

    public String defaultTenantId() {
        return defaultTenantId;
    }

    private String normalizeMode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of(SINGLE_TENANT, MULTI_TENANT).contains(normalized)) {
            throw new IllegalArgumentException("AIMALL_TENANT_MODE 只支持 SINGLE_TENANT 或 MULTI_TENANT");
        }
        return normalized;
    }

    private String requireId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("tenantId 格式不正确");
        }
        return normalized;
    }
}
