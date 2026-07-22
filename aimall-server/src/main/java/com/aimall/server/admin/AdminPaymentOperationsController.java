package com.aimall.server.admin;

import com.aimall.server.common.ApiResponse;
import com.aimall.server.common.RequestUtils;
import com.aimall.server.entity.LatePaymentCase;
import com.aimall.server.entity.OmsRefundRecord;
import com.aimall.server.entity.OutboxEvent;
import com.aimall.server.mapper.LatePaymentCaseMapper;
import com.aimall.server.mapper.OmsRefundRecordMapper;
import com.aimall.server.mapper.OutboxEventMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import com.aimall.server.service.impl.PaymentOperationsObservabilityService;
import com.aimall.server.security.AdminPermissions;
import com.aimall.server.security.RequireAdminPermission;
import com.aimall.server.admin.dto.OperationNoteRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/admin/payment-operations")
@RequireAdminPermission(AdminPermissions.PAYMENT_VIEW)
public class AdminPaymentOperationsController {
    private final OutboxEventMapper outboxMapper;
    private final OmsRefundRecordMapper refundMapper;
    private final LatePaymentCaseMapper latePaymentMapper;
    private final PaymentOperationsObservabilityService observabilityService;

    public AdminPaymentOperationsController(OutboxEventMapper outboxMapper,
                                            OmsRefundRecordMapper refundMapper,
                                            LatePaymentCaseMapper latePaymentMapper,
                                            PaymentOperationsObservabilityService observabilityService) {
        this.outboxMapper = outboxMapper;
        this.refundMapper = refundMapper;
        this.latePaymentMapper = latePaymentMapper;
        this.observabilityService = observabilityService;
    }

    @GetMapping("/metrics")
    public ApiResponse<Map<String, Object>> metrics() {
        return ApiResponse.success(observabilityService.snapshot());
    }

    @GetMapping("/outbox")
    public ApiResponse<List<OutboxEvent>> outbox(
            @RequestParam(defaultValue = "DEAD_LETTER") String status,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(outboxMapper.selectList(new LambdaQueryWrapper<OutboxEvent>()
                .eq(OutboxEvent::getStatus, status.trim().toUpperCase())
                .orderByDesc(OutboxEvent::getId).last("limit " + clamp(limit))));
    }

    @PostMapping("/outbox/{id}/retry")
    @RequireAdminPermission(AdminPermissions.PAYMENT_OPERATE)
    public ApiResponse<Map<String, Object>> retryOutbox(@PathVariable Long id) {
        if (outboxMapper.retryDeadLetter(id) != 1) throw new RuntimeException("Outbox 事件不在可重试状态");
        return ApiResponse.success(Map.of("id", id, "status", "RETRY_WAIT"));
    }

    @PostMapping("/outbox/{id}/manual-review")
    @RequireAdminPermission(AdminPermissions.PAYMENT_OPERATE)
    public ApiResponse<Map<String, Object>> moveOutboxToManualReview(
            @PathVariable Long id, @Valid @RequestBody OperationNoteRequest params) {
        if (outboxMapper.moveToManualReview(id, params.reason()) != 1) {
            throw new RuntimeException("Outbox 事件不在可人工复核状态");
        }
        return ApiResponse.success(Map.of("id", id, "status", "MANUAL_REVIEW"));
    }

    @PostMapping("/outbox/{id}/close")
    @RequireAdminPermission(AdminPermissions.PAYMENT_OPERATE)
    public ApiResponse<Map<String, Object>> closeOutbox(@PathVariable Long id,
                                                        @Valid @RequestBody OperationNoteRequest params) {
        if (outboxMapper.closeDeadLetter(id, params.reason()) != 1) throw new RuntimeException("Outbox 事件不在可关闭状态");
        return ApiResponse.success(Map.of("id", id, "status", "CANCELLED"));
    }

    @GetMapping("/refund-failures")
    public ApiResponse<List<OmsRefundRecord>> refundFailures(@RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(refundMapper.selectList(new LambdaQueryWrapper<OmsRefundRecord>()
                .in(OmsRefundRecord::getRefundStatus, "FAILED", "REFUND_UNKNOWN")
                .orderByDesc(OmsRefundRecord::getId).last("limit " + clamp(limit))));
    }

    @GetMapping("/late-payments")
    public ApiResponse<List<LatePaymentCase>> latePayments(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        LambdaQueryWrapper<LatePaymentCase> query = new LambdaQueryWrapper<LatePaymentCase>()
                .orderByDesc(LatePaymentCase::getId).last("limit " + clamp(limit));
        if (status != null && !status.isBlank()) query.eq(LatePaymentCase::getStatus, status.trim().toUpperCase());
        return ApiResponse.success(latePaymentMapper.selectList(query));
    }

    private int clamp(int limit) { return Math.max(1, Math.min(limit, 200)); }
}
