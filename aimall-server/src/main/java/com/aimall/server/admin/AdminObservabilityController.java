package com.aimall.server.admin;

import com.aimall.server.common.ApiResponse;
import com.aimall.server.observability.OperationalMetricsService;
import com.aimall.server.security.AdminPermissions;
import com.aimall.server.security.RequireAdminPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/observability")
@RequireAdminPermission(AdminPermissions.ADMIN_DIAGNOSTICS)
public class AdminObservabilityController {
    private final OperationalMetricsService metricsService;

    public AdminObservabilityController(OperationalMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping
    public ApiResponse<Map<String, Long>> snapshot() {
        metricsService.refresh();
        return ApiResponse.success(metricsService.snapshot());
    }
}
