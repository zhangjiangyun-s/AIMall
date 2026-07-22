package com.aimall.server.service.impl;

import com.aimall.server.admin.dto.ReconciliationCorrectionRequest;
import com.aimall.server.entity.PaymentCorrectionEvent;
import com.aimall.server.entity.PaymentReconciliationItem;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.mapper.PaymentCorrectionEventMapper;
import com.aimall.server.mapper.PaymentReconciliationItemMapper;
import com.aimall.server.mapper.OmsRefundRecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class PaymentReconciliationWorkflowService {
    private final PaymentReconciliationItemMapper itemMapper;
    private final PaymentCorrectionEventMapper correctionMapper;
    private final OmsOrderMapper orderMapper;
    private final OmsRefundRecordMapper refundMapper;
    private final ObjectMapper objectMapper;

    public PaymentReconciliationWorkflowService(PaymentReconciliationItemMapper itemMapper,
                                                PaymentCorrectionEventMapper correctionMapper,
                                                OmsOrderMapper orderMapper,
                                                OmsRefundRecordMapper refundMapper) {
        this(itemMapper, correctionMapper, orderMapper, refundMapper, new ObjectMapper());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentReconciliationWorkflowService(PaymentReconciliationItemMapper itemMapper,
                                                PaymentCorrectionEventMapper correctionMapper,
                                                OmsOrderMapper orderMapper,
                                                OmsRefundRecordMapper refundMapper,
                                                ObjectMapper objectMapper) {
        this.itemMapper = itemMapper;
        this.correctionMapper = correctionMapper;
        this.orderMapper = orderMapper;
        this.refundMapper = refundMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> claim(Long itemId, Long operatorId) {
        PaymentReconciliationItem item = itemMapper.selectById(itemId);
        if (item == null) throw new RuntimeException("对账差异不存在");
        if (itemMapper.claim(itemId, operatorId) != 1) {
            throw new RuntimeException("对账差异不存在、已被认领或已结束");
        }
        if (item.getRefundRecordId() != null
                && refundMapper.claimReconciliation(item.getRefundRecordId(), operatorId) != 1) {
            throw new RuntimeException("退款差异已被其他人认领或状态已变化");
        }
        return Map.of("id", itemId, "status", "CLAIMED", "claimedBy", operatorId);
    }

    @Transactional
    public PaymentCorrectionEvent submitCorrection(Long itemId, Long operatorId,
                                                   ReconciliationCorrectionRequest request) {
        PaymentReconciliationItem item = itemMapper.selectById(itemId);
        if (item == null || !"CLAIMED".equals(item.getResolutionStatus())
                || !operatorId.equals(item.getClaimedBy())) {
            throw new RuntimeException("只能由当前认领人提交修正事件");
        }
        PaymentCorrectionEvent event = new PaymentCorrectionEvent();
        event.setEventNo("CORR-" + UUID.randomUUID().toString().replace("-", "").toUpperCase());
        event.setReconciliationItemId(itemId);
        event.setCorrectionType(request.correctionType());
        event.setReason(request.reason());
        event.setEvidence(request.evidence());
        event.setOriginalValueJson(normalizeJson(request.originalValueJson(), "原值"));
        event.setProposedValueJson(normalizeJson(request.proposedValueJson(), "建议值"));
        event.setOperatorId(operatorId);
        event.setStatus("PENDING_REVIEW");
        correctionMapper.insert(event);
        if (itemMapper.submitForReview(itemId, operatorId) != 1) {
            throw new RuntimeException("对账差异状态已变化，无法提交复核");
        }
        return event;
    }

    @Transactional
    public Map<String, Object> review(Long itemId, Long eventId, Long reviewerId, boolean approved,
                                      String approvalNo, String note) {
        PaymentCorrectionEvent event = correctionMapper.selectById(eventId);
        if (event == null || !itemId.equals(event.getReconciliationItemId())) {
            throw new RuntimeException("修正事件不存在");
        }
        if (reviewerId.equals(event.getOperatorId())) {
            throw new RuntimeException("提交人与复核人必须分离");
        }
        String eventStatus = approved ? "APPROVED" : "REJECTED";
        if (correctionMapper.review(eventId, itemId, eventStatus, reviewerId, approvalNo, note) != 1) {
            throw new RuntimeException("修正事件已被复核或状态已变化");
        }
        int transitioned = approved
                ? itemMapper.closeAfterReview(itemId, reviewerId, approvalNo, note)
                : itemMapper.returnAfterRejection(itemId, reviewerId, note);
        if (transitioned != 1) {
            throw new RuntimeException("对账差异复核状态转换失败");
        }
        if (approved) {
            PaymentReconciliationItem item = itemMapper.selectById(itemId);
            if (item != null && item.getRefundRecordId() != null) {
                refundMapper.clearReconciliation(item.getRefundRecordId());
            }
            releaseHoldWhenClear(item == null ? null : item.getOrderId());
        }
        return Map.of("id", itemId, "eventId", eventId,
                "status", approved ? "CLOSED" : "CLAIMED", "reviewedBy", reviewerId);
    }

    @Transactional
    public Map<String, Object> escalate(Long itemId, Long operatorId, String reason, String evidence) {
        PaymentReconciliationItem item = itemMapper.selectById(itemId);
        if (item == null) throw new RuntimeException("对账差异不存在");
        String note = reason + " | evidence=" + evidence;
        if (itemMapper.escalate(itemId, operatorId, note) != 1) {
            throw new RuntimeException("对账差异不存在或已结束");
        }
        if (item.getRefundRecordId() != null) refundMapper.escalateReconciliation(item.getRefundRecordId());
        return Map.of("id", itemId, "status", "ESCALATED");
    }

    private void releaseHoldWhenClear(Long orderId) {
        if (orderId == null) return;
        long unresolved = itemMapper.selectCount(new LambdaQueryWrapper<PaymentReconciliationItem>()
                .eq(PaymentReconciliationItem::getOrderId, orderId)
                .in(PaymentReconciliationItem::getResolutionStatus,
                        "OPEN", "CLAIMED", "PENDING_REVIEW", "ESCALATED"));
        if (unresolved == 0) orderMapper.releaseFinancialHold(orderId);
    }

    private String normalizeJson(String value, String label) {
        try {
            return objectMapper.writeValueAsString(objectMapper.readTree(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(label + "必须是合法 JSON");
        }
    }
}
