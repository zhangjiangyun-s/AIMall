package com.aimall.server.admin;

import com.aimall.server.common.ApiResponse;
import com.aimall.server.common.RequestUtils;
import com.aimall.server.entity.OmsOrderItem;
import com.aimall.server.entity.OmsOrderReturnApply;
import com.aimall.server.entity.OmsRefundRecord;
import com.aimall.server.mapper.OmsOrderItemMapper;
import com.aimall.server.mapper.OmsRefundRecordMapper;
import com.aimall.server.service.ReturnApplyService;
import com.aimall.server.security.AdminPermissions;
import com.aimall.server.security.RequireAdminPermission;
import com.aimall.server.admin.dto.ReturnReviewRequest;
import com.aimall.server.admin.dto.ReturnRefundRequest;
import com.aimall.server.admin.dto.ReturnInspectionRequest;
import com.aimall.server.admin.dto.ReturnHandleNoteRequest;
import jakarta.validation.Valid;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/returns")
@RequireAdminPermission(AdminPermissions.RETURN_VIEW)
public class AdminReturnController {

    private final ReturnApplyService returnApplyService;
    private final OmsOrderItemMapper orderItemMapper;
    private final OmsRefundRecordMapper refundRecordMapper;
    private final com.aimall.server.service.impl.ReturnWorkflowService workflowService;

    public AdminReturnController(
            ReturnApplyService returnApplyService,
            OmsOrderItemMapper orderItemMapper,
            OmsRefundRecordMapper refundRecordMapper,
            com.aimall.server.service.impl.ReturnWorkflowService workflowService
    ) {
        this.returnApplyService = returnApplyService;
        this.orderItemMapper = orderItemMapper;
        this.refundRecordMapper = refundRecordMapper;
        this.workflowService = workflowService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.success(returnApplyService.listAll().stream().map(this::toMap).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.success(toDetailMap(returnApplyService.getById(id)));
    }

    @PostMapping("/{id}/review")
    @RequireAdminPermission(AdminPermissions.RETURN_REVIEW)
    public ApiResponse<Map<String, Object>> review(
            @PathVariable Long id,
            @Valid @RequestBody ReturnReviewRequest params
    ) {
        return ApiResponse.success(toMap(returnApplyService.review(id, params.approved(), params.handleNote())));
    }

    @PostMapping("/{id}/refund")
    @RequireAdminPermission(AdminPermissions.RETURN_REFUND)
    public ApiResponse<Map<String, Object>> refund(
            @PathVariable Long id,
            @Valid @RequestBody ReturnRefundRequest params
    ) {
        return ApiResponse.success(toMap(returnApplyService.refund(
                id,
                params.requestId(), params.handleNote()
        )));
    }

    @PostMapping("/{id}/inspection")
    @RequireAdminPermission(AdminPermissions.RETURN_REVIEW)
    public ApiResponse<Map<String,Object>> inspect(
            @PathVariable Long id,
            @Valid @RequestBody ReturnInspectionRequest params
    ) {
        String login = cn.dev33.satoken.stp.StpUtil.getLoginIdAsString();
        Long adminId = Long.valueOf(login.substring("admin_".length()));
        return ApiResponse.success(toMap(workflowService.inspect(adminId, id, params.accepted(), params.note())));
    }

    @GetMapping("/{id}/events")
    public ApiResponse<List<com.aimall.server.entity.OmsReturnStatusEvent>> events(@PathVariable Long id){return ApiResponse.success(workflowService.events(id));}

    @PostMapping("/{id}/refund/retry")
    @RequireAdminPermission(AdminPermissions.RETURN_REFUND)
    public ApiResponse<Map<String, Object>> retryRefund(
            @PathVariable Long id,
            @Valid @RequestBody ReturnHandleNoteRequest params
    ) {
        return ApiResponse.success(toMap(returnApplyService.retryFailedRefund(
                id,
                params.handleNote()
        )));
    }

    @PostMapping("/{id}/refund/close")
    @RequireAdminPermission(AdminPermissions.RETURN_REFUND)
    public ApiResponse<Map<String, Object>> closeRefund(
            @PathVariable Long id,
            @Valid @RequestBody ReturnHandleNoteRequest params
    ) {
        return ApiResponse.success(toMap(returnApplyService.closeFailedRefund(
                id,
                params.handleNote()
        )));
    }

    private Map<String, Object> toDetailMap(OmsOrderReturnApply apply) {
        Map<String, Object> data = toMap(apply);
        List<OmsOrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OmsOrderItem>().eq(OmsOrderItem::getOrderId, apply.getOrderId())
        );
        data.put("items", items.stream().map(item -> {
            Map<String, Object> row = new HashMap<>();
            row.put("productId", item.getProductId());
            row.put("productName", item.getProductName());
            row.put("productBrand", item.getProductBrand() == null ? "" : item.getProductBrand());
            row.put("quantity", item.getProductQuantity());
            row.put("price", item.getProductPrice());
            row.put("productAttr", item.getProductAttr() == null ? "" : item.getProductAttr());
            row.put("realAmount", item.getRealAmount());
            return row;
        }).toList());
        data.put("evidence", workflowService.evidence(apply.getId()));
        data.put("statusEvents", workflowService.events(apply.getId()));
        return data;
    }

    private Map<String, Object> toMap(OmsOrderReturnApply apply) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", apply.getId());
        data.put("orderId", apply.getOrderId());
        data.put("orderNo", apply.getOrderSn());
        data.put("memberId", apply.getMemberId());
        data.put("type", apply.getType());
        data.put("status", statusCode(apply.getStatus()));
        data.put("statusText", statusText(apply.getStatus()));
        data.put("reason", apply.getReason());
        data.put("description", apply.getDescription() == null ? "" : apply.getDescription());
        data.put("returnAmount", apply.getReturnAmount());
        data.put("handleNote", apply.getHandleNote() == null ? "" : apply.getHandleNote());
        data.put("handleTime", formatTime(apply.getHandleTime()));
        data.put("createTime", formatTime(apply.getCreateTime()));
        data.put("updateTime", formatTime(apply.getUpdateTime()));
        data.put("returnCarrier", apply.getReturnCarrier() == null ? "" : apply.getReturnCarrier());
        data.put("returnTrackingNo", apply.getReturnTrackingNo() == null ? "" : apply.getReturnTrackingNo());
        data.put("inspectionResult", apply.getInspectionResult() == null ? "" : apply.getInspectionResult());
        data.put("inspectionNote", apply.getInspectionNote() == null ? "" : apply.getInspectionNote());
        data.put("slaDeadline", formatTime(apply.getSlaDeadline()));
        data.put("slaOverdue", apply.getSlaOverdue() != null && apply.getSlaOverdue() == 1);
        OmsRefundRecord refund = refundRecordMapper.selectOne(
                new LambdaQueryWrapper<OmsRefundRecord>()
                        .eq(OmsRefundRecord::getReturnApplyId, apply.getId())
                        .last("limit 1")
        );
        data.put("refundStatus", refund == null ? "" : refund.getRefundStatus());
        data.put("refundFailureReason", refund == null || refund.getFailureReason() == null ? "" : refund.getFailureReason());
        data.put("refundRetryCount", refund == null ? 0 : refund.getRetryCount());
        data.put("refundManualRetryCount", refund == null ? 0 : refund.getManualRetryCount());
        return data;
    }

    private String statusCode(Integer status) {
        if (status == null) {
            return "UNKNOWN";
        }
        return switch (status) {
            case 0 -> "APPLIED";
            case 10 -> "REVIEWING";
            case 1 -> "APPROVED";
            case 2 -> "REJECTED";
            case 3 -> "REFUNDED";
            case 4 -> "CANCELLED";
            case 5 -> "REFUNDING";
            case 6 -> "REFUND_FAILED_CLOSED";
            case 7 -> "RETURNING";
            case 8 -> "RECEIVED";
            case 9 -> "CLOSED";
            default -> "UNKNOWN";
        };
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 0 -> "已申请";
            case 10 -> "审核中";
            case 1 -> "已通过";
            case 2 -> "已驳回";
            case 3 -> "已退款";
            case 4 -> "已取消";
            case 5 -> "退款处理中";
            case 6 -> "退款失败已关闭";
            case 7 -> "退货运输中";
            case 8 -> "已收货验货";
            case 9 -> "已关闭";
            default -> "未知";
        };
    }

    private String formatTime(java.time.LocalDateTime time) {
        return time == null ? "" : time.toString().replace("T", " ");
    }
}
