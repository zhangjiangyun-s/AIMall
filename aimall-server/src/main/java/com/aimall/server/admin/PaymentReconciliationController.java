package com.aimall.server.admin;

import com.aimall.server.common.ApiResponse;
import com.aimall.server.service.impl.PaymentReconciliationService;
import com.aimall.server.security.AdminPermissions;
import com.aimall.server.security.RequireAdminPermission;
import com.aimall.server.admin.dto.ReconciliationCorrectionRequest;
import com.aimall.server.admin.dto.ReconciliationReviewRequest;
import com.aimall.server.admin.dto.ReconciliationEscalateRequest;
import com.aimall.server.service.impl.PaymentReconciliationWorkflowService;
import com.aimall.server.entity.PaymentCorrectionEvent;
import com.aimall.server.mapper.PaymentCorrectionEventMapper;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.List;
import cn.dev33.satoken.stp.StpUtil;
import com.aimall.server.common.RequestUtils;
import com.aimall.server.entity.PaymentReconciliationBatch;
import com.aimall.server.entity.PaymentReconciliationItem;
import com.aimall.server.mapper.PaymentReconciliationBatchMapper;
import com.aimall.server.mapper.PaymentReconciliationItemMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

@RestController
@RequestMapping("/api/admin/payment-reconciliation")
@RequireAdminPermission(AdminPermissions.PAYMENT_RECONCILE)
public class PaymentReconciliationController {
    private final PaymentReconciliationService service;
    private final PaymentReconciliationBatchMapper batchMapper;
    private final PaymentReconciliationItemMapper itemMapper;
    private final PaymentReconciliationWorkflowService workflowService;
    private final PaymentCorrectionEventMapper correctionMapper;

    public PaymentReconciliationController(PaymentReconciliationService service,
                                           PaymentReconciliationBatchMapper batchMapper,
                                           PaymentReconciliationItemMapper itemMapper,
                                           PaymentReconciliationWorkflowService workflowService,
                                           PaymentCorrectionEventMapper correctionMapper) {
        this.service = service;
        this.batchMapper = batchMapper;
        this.itemMapper = itemMapper;
        this.workflowService = workflowService;
        this.correctionMapper = correctionMapper;
    }

    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(service.run(date == null ? LocalDate.now().minusDays(1) : date));
    }

    @GetMapping("/batches")
    public ApiResponse<List<PaymentReconciliationBatch>> batches(@RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(batchMapper.selectList(new LambdaQueryWrapper<PaymentReconciliationBatch>()
                .orderByDesc(PaymentReconciliationBatch::getId)
                .last("limit " + Math.max(1, Math.min(limit, 200)))));
    }

    @GetMapping("/batches/{batchId}/items")
    public ApiResponse<List<PaymentReconciliationItem>> items(
            @PathVariable Long batchId, @RequestParam(required = false) String status) {
        LambdaQueryWrapper<PaymentReconciliationItem> query = new LambdaQueryWrapper<PaymentReconciliationItem>()
                .eq(PaymentReconciliationItem::getBatchId, batchId)
                .orderByDesc(PaymentReconciliationItem::getId);
        if (status != null && !status.isBlank()) {
            query.eq(PaymentReconciliationItem::getResolutionStatus, status.trim().toUpperCase());
        }
        return ApiResponse.success(itemMapper.selectList(query));
    }

    @PostMapping("/items/{id}/claim")
    public ApiResponse<Map<String, Object>> claim(@PathVariable Long id) {
        return ApiResponse.success(workflowService.claim(id, operatorId()));
    }

    @PostMapping("/items/{id}/corrections")
    public ApiResponse<PaymentCorrectionEvent> submitCorrection(
            @PathVariable Long id, @Valid @RequestBody ReconciliationCorrectionRequest params) {
        return ApiResponse.success(workflowService.submitCorrection(id, operatorId(), params));
    }

    @GetMapping("/items/{id}/corrections")
    public ApiResponse<List<PaymentCorrectionEvent>> corrections(@PathVariable Long id) {
        return ApiResponse.success(correctionMapper.selectList(
                new LambdaQueryWrapper<PaymentCorrectionEvent>()
                        .eq(PaymentCorrectionEvent::getReconciliationItemId, id)
                        .orderByDesc(PaymentCorrectionEvent::getId)));
    }

    @PostMapping("/items/{id}/corrections/{eventId}/review")
    public ApiResponse<Map<String, Object>> review(
            @PathVariable Long id, @PathVariable Long eventId,
            @Valid @RequestBody ReconciliationReviewRequest params) {
        return ApiResponse.success(workflowService.review(id, eventId, operatorId(), params.approved(),
                params.approvalNo(), params.note()));
    }

    @PostMapping("/items/{id}/escalate")
    public ApiResponse<Map<String, Object>> escalate(
            @PathVariable Long id, @Valid @RequestBody ReconciliationEscalateRequest params) {
        return ApiResponse.success(workflowService.escalate(id, operatorId(), params.reason(), params.evidence()));
    }

    private Long operatorId() {
        String loginId = StpUtil.getLoginIdAsString();
        if (loginId == null || !loginId.startsWith("admin_")) throw new RuntimeException("无管理员权限");
        return Long.parseLong(loginId.substring("admin_".length()));
    }
}
