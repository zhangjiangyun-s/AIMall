package com.aimall.server.service.impl;

import com.aimall.server.entity.LatePaymentCase;
import com.aimall.server.mapper.LatePaymentCaseMapper;
import com.aimall.server.service.RefundGateway;
import org.springframework.stereotype.Service;

@Service
public class LatePaymentProcessor {
    private final LatePaymentCaseMapper caseMapper;
    private final RefundGateway refundGateway;
    private final LatePaymentStateService stateService;

    public LatePaymentProcessor(LatePaymentCaseMapper caseMapper, RefundGateway refundGateway,
                                LatePaymentStateService stateService) {
        this.caseMapper = caseMapper;
        this.refundGateway = refundGateway;
        this.stateService = stateService;
    }

    public void process(Long caseId) {
        LatePaymentCase value = caseMapper.selectById(caseId);
        if (value == null || "REFUNDED".equals(value.getStatus())) return;
        if ("REFUND_UNKNOWN".equals(value.getStatus())) {
            query(value);
            return;
        }
        if (caseMapper.claimRefund(caseId) != 1) return;
        value = caseMapper.selectById(caseId);
        try {
            RefundGateway.RefundResult result = refundGateway.refund(
                    value.getRefundRequestId(), value.getOrderSn(), value.getAmount());
            stateService.finalizeRefund(value, result.transactionNo());
        } catch (RuntimeException exception) {
            caseMapper.markUnknown(caseId, message(exception));
            throw exception;
        }
    }

    private void query(LatePaymentCase value) {
        if (caseMapper.claimQuery(value.getId()) != 1) return;
        try {
            RefundGateway.QueryRefundResult result = refundGateway.queryRefund(
                    value.getRefundRequestId(), value.getOrderSn(), value.getAmount());
            if ("SUCCEEDED".equals(result.status())) {
                stateService.finalizeRefund(value, result.transactionNo());
                return;
            }
            if ("NOT_FOUND".equals(result.status())) {
                caseMapper.markNotFound(value.getId(), "渠道未找到晚到支付退款");
            } else {
                caseMapper.markUnknown(value.getId(), "退款查单状态: " + result.status());
            }
            throw new IllegalStateException("晚到支付退款尚未收敛: " + result.status());
        } catch (RuntimeException exception) {
            LatePaymentCase current = caseMapper.selectById(value.getId());
            if (current != null && "QUERYING".equals(current.getStatus())) {
                caseMapper.markUnknown(value.getId(), message(exception));
            }
            throw exception;
        }
    }

    private String message(RuntimeException exception) {
        String value = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return value.substring(0, Math.min(1000, value.length()));
    }
}
