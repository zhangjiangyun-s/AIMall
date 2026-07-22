package com.aimall.server.admin;

import com.aimall.server.common.ApiResponse;
import com.aimall.server.entity.AiActionExecution;
import com.aimall.server.service.AiActionExecutionService;
import com.aimall.server.security.AdminPermissions;
import com.aimall.server.security.RequireAdminPermission;
import com.aimall.server.admin.dto.AiRecoveryResolveRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/ai-action-recovery")
@RequireAdminPermission(AdminPermissions.AI_RECOVERY)
public class AdminAiActionRecoveryController {

    private final AiActionExecutionService executionService;

    public AdminAiActionRecoveryController(AiActionExecutionService executionService) {
        this.executionService = executionService;
    }

    @GetMapping
    public ApiResponse<List<AiActionExecution>> list(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(executionService.listRecoveryRequired(limit));
    }

    @PostMapping("/{actionId}/resolve")
    public ApiResponse<Void> resolve(
            @PathVariable String actionId,
            @Valid @RequestBody AiRecoveryResolveRequest request
    ) {
        executionService.resolveRecovery(actionId, request.succeeded(), request.resolvedResult(), request.note());
        return ApiResponse.success(null);
    }
}
