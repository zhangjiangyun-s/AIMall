package com.aimall.server.service.impl;

import com.aimall.server.entity.LatePaymentCase;
import com.aimall.server.entity.OmsRefundRecord;
import com.aimall.server.entity.OutboxEvent;
import com.aimall.server.entity.PaymentReconciliationItem;
import com.aimall.server.mapper.LatePaymentCaseMapper;
import com.aimall.server.mapper.OmsRefundRecordMapper;
import com.aimall.server.mapper.OutboxEventMapper;
import com.aimall.server.mapper.PaymentReconciliationItemMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PaymentOperationsObservabilityService {
    private static final Logger log = LoggerFactory.getLogger(PaymentOperationsObservabilityService.class);
    private final OutboxEventMapper outboxMapper;
    private final OmsRefundRecordMapper refundMapper;
    private final LatePaymentCaseMapper latePaymentMapper;
    private final PaymentReconciliationItemMapper reconciliationItemMapper;

    public PaymentOperationsObservabilityService(OutboxEventMapper outboxMapper,
                                                 OmsRefundRecordMapper refundMapper,
                                                 LatePaymentCaseMapper latePaymentMapper,
                                                 PaymentReconciliationItemMapper reconciliationItemMapper) {
        this.outboxMapper = outboxMapper;
        this.refundMapper = refundMapper;
        this.latePaymentMapper = latePaymentMapper;
        this.reconciliationItemMapper = reconciliationItemMapper;
    }

    public Map<String, Object> snapshot() {
        long outboxDead = outboxMapper.selectCount(new LambdaQueryWrapper<OutboxEvent>()
                .eq(OutboxEvent::getStatus, "DEAD_LETTER"));
        long outboxRetry = outboxMapper.selectCount(new LambdaQueryWrapper<OutboxEvent>()
                .eq(OutboxEvent::getStatus, "RETRY"));
        long refundUnresolved = refundMapper.selectCount(new LambdaQueryWrapper<OmsRefundRecord>()
                .in(OmsRefundRecord::getRefundStatus, "FAILED", "REFUND_UNKNOWN"));
        long latePaymentUnresolved = latePaymentMapper.selectCount(new LambdaQueryWrapper<LatePaymentCase>()
                .notIn(LatePaymentCase::getStatus, "REFUNDED", "CLOSED"));
        long reconciliationOpen = reconciliationItemMapper.selectCount(
                new LambdaQueryWrapper<PaymentReconciliationItem>()
                        .eq(PaymentReconciliationItem::getResolutionStatus, "OPEN"));
        boolean healthy = outboxDead == 0 && refundUnresolved == 0 && latePaymentUnresolved == 0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", healthy ? "UP" : "DEGRADED");
        result.put("outboxDeadLetter", outboxDead);
        result.put("outboxRetryWait", outboxRetry);
        result.put("refundUnresolved", refundUnresolved);
        result.put("latePaymentUnresolved", latePaymentUnresolved);
        result.put("reconciliationOpen", reconciliationOpen);
        return result;
    }

    @Scheduled(fixedDelayString = "${aimall.payment.operations-alert-ms:60000}")
    public void alert() {
        Map<String, Object> metrics = snapshot();
        if (!"UP".equals(metrics.get("status"))) {
            log.error("PAYMENT_OPERATIONS_ALERT {}", metrics);
        } else if (((Number) metrics.get("outboxRetryWait")).longValue() > 0
                || ((Number) metrics.get("reconciliationOpen")).longValue() > 0) {
            log.warn("PAYMENT_OPERATIONS_PENDING {}", metrics);
        }
    }
}
