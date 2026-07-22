package com.aimall.server.user;

import cn.dev33.satoken.stp.StpUtil;
import com.aimall.server.common.ApiResponse;
import com.aimall.server.common.RequestUtils;
import com.aimall.server.user.dto.ReturnApplyRequest;
import com.aimall.server.user.dto.ReturnEvidenceRequest;
import com.aimall.server.user.dto.ReturnLogisticsRequest;
import com.aimall.server.entity.OmsOrderItem;
import com.aimall.server.entity.OmsOrderReturnApply;
import com.aimall.server.mapper.OmsOrderItemMapper;
import com.aimall.server.mapper.OmsOrderReturnItemMapper;
import com.aimall.server.entity.OmsOrderReturnItem;
import com.aimall.server.service.ReturnApplyService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/returns")
public class ReturnApplyController {

    private final ReturnApplyService returnApplyService;
    private final OmsOrderItemMapper orderItemMapper;
    private final OmsOrderReturnItemMapper returnItemMapper;
    private final com.aimall.server.service.impl.ReturnWorkflowService workflowService;

    public ReturnApplyController(
            ReturnApplyService returnApplyService,
            OmsOrderItemMapper orderItemMapper,
            OmsOrderReturnItemMapper returnItemMapper,
            com.aimall.server.service.impl.ReturnWorkflowService workflowService
    ) {
        this.returnApplyService = returnApplyService;
        this.orderItemMapper = orderItemMapper;
        this.returnItemMapper = returnItemMapper;
        this.workflowService = workflowService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        long memberId = StpUtil.getLoginIdAsLong();
        return ApiResponse.success(returnApplyService.listByMember(memberId).stream().map(this::toMap).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        long memberId = StpUtil.getLoginIdAsLong();
        return ApiResponse.success(toDetailMap(returnApplyService.getByIdForMember(memberId, id)));
    }

    @GetMapping("/order/{orderId}")
    public ApiResponse<Map<String, Object>> latestByOrder(@PathVariable Long orderId) {
        long memberId = StpUtil.getLoginIdAsLong();
        OmsOrderReturnApply apply = returnApplyService.getLatestByOrderForMember(memberId, orderId);
        return ApiResponse.success(apply == null ? null : toMap(apply));
    }

    @PostMapping("/apply")
    public ApiResponse<Map<String, Object>> apply(@Valid @RequestBody ReturnApplyRequest params) {
        long memberId = StpUtil.getLoginIdAsLong();
        OmsOrderReturnApply apply = returnApplyService.apply(
                memberId,
                params.orderId(),
                params.reason(),
                params.description(),
                params.serviceItems(),
                params.type()
        );
        return ApiResponse.success(toDetailMap(apply));
    }

    @PostMapping("/{id}/evidence")
    public ApiResponse<com.aimall.server.entity.OmsReturnEvidence> addEvidence(
            @PathVariable Long id,
            @Valid @RequestBody ReturnEvidenceRequest params
    ) {
        return ApiResponse.success(workflowService.addEvidence(
                StpUtil.getLoginIdAsLong(), id, params.mediaType(), params.mediaUrl()
        ));
    }

    @PostMapping("/{id}/logistics")
    public ApiResponse<Map<String,Object>> logistics(
            @PathVariable Long id,
            @Valid @RequestBody ReturnLogisticsRequest params
    ) {
        return ApiResponse.success(toMap(workflowService.submitLogistics(
                StpUtil.getLoginIdAsLong(), id, params.carrier(), params.trackingNo()
        )));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable Long id) {
        long memberId = StpUtil.getLoginIdAsLong();
        return ApiResponse.success(toMap(returnApplyService.cancel(memberId, id)));
    }

    private Map<String, Object> toDetailMap(OmsOrderReturnApply apply) {
        Map<String, Object> data = toMap(apply);
        List<OmsOrderReturnItem> returnItems = returnItemMapper.selectList(
                new LambdaQueryWrapper<OmsOrderReturnItem>().eq(OmsOrderReturnItem::getReturnApplyId, apply.getId())
        );
        Map<Long, OmsOrderItem> orderItems = orderItemMapper.selectBatchIds(
                returnItems.stream().map(OmsOrderReturnItem::getOrderItemId).toList()
        ).stream().collect(java.util.stream.Collectors.toMap(OmsOrderItem::getId, item -> item));
        data.put("items", returnItems.stream().map(returnItem -> {
            OmsOrderItem item = orderItems.get(returnItem.getOrderItemId());
            Map<String, Object> row = new HashMap<>();
            row.put("orderItemId", returnItem.getOrderItemId());
            row.put("productId", returnItem.getProductId());
            row.put("productName", item == null ? "" : item.getProductName());
            row.put("productBrand", item == null || item.getProductBrand() == null ? "" : item.getProductBrand());
            row.put("quantity", returnItem.getQuantity());
            row.put("refundAmount", returnItem.getRefundAmount());
            row.put("productAttr", item == null || item.getProductAttr() == null ? "" : item.getProductAttr());
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
