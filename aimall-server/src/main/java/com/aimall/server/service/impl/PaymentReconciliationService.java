package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsPaymentRecord;
import com.aimall.server.entity.OmsRefundRecord;
import com.aimall.server.entity.PaymentReconciliationBatch;
import com.aimall.server.entity.PaymentReconciliationItem;
import com.aimall.server.entity.PaymentCallbackEvent;
import com.aimall.server.mapper.OmsPaymentRecordMapper;
import com.aimall.server.mapper.OmsRefundRecordMapper;
import com.aimall.server.mapper.PaymentReconciliationBatchMapper;
import com.aimall.server.mapper.PaymentReconciliationItemMapper;
import com.aimall.server.mapper.PaymentCallbackEventMapper;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.payment.AlipayPaymentGateway;
import com.aimall.server.service.RefundGateway;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentReconciliationService {
    private final PaymentReconciliationBatchMapper batchMapper;
    private final PaymentReconciliationItemMapper itemMapper;
    private final OmsPaymentRecordMapper paymentMapper;
    private final OmsRefundRecordMapper refundMapper;
    private final PaymentCallbackEventMapper evidenceMapper;
    private final OmsOrderMapper orderMapper;
    private final AlipayPaymentGateway paymentGateway;
    private final RefundGateway refundGateway;
    private final String provider;

    public PaymentReconciliationService(PaymentReconciliationBatchMapper batchMapper,
                                        PaymentReconciliationItemMapper itemMapper,
                                        OmsPaymentRecordMapper paymentMapper, OmsRefundRecordMapper refundMapper,
                                        PaymentCallbackEventMapper evidenceMapper) {
        this(batchMapper, itemMapper, paymentMapper, refundMapper, evidenceMapper, null, null, null,
                "ALIPAY_SANDBOX");
    }

    public PaymentReconciliationService(PaymentReconciliationBatchMapper batchMapper,
                                        PaymentReconciliationItemMapper itemMapper,
                                        OmsPaymentRecordMapper paymentMapper, OmsRefundRecordMapper refundMapper,
                                        PaymentCallbackEventMapper evidenceMapper, OmsOrderMapper orderMapper,
                                        AlipayPaymentGateway paymentGateway, RefundGateway refundGateway) {
        this(batchMapper, itemMapper, paymentMapper, refundMapper, evidenceMapper, orderMapper,
                paymentGateway, refundGateway, "ALIPAY_SANDBOX");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentReconciliationService(PaymentReconciliationBatchMapper batchMapper,
                                        PaymentReconciliationItemMapper itemMapper,
                                        OmsPaymentRecordMapper paymentMapper, OmsRefundRecordMapper refundMapper,
                                        PaymentCallbackEventMapper evidenceMapper, OmsOrderMapper orderMapper,
                                        AlipayPaymentGateway paymentGateway, RefundGateway refundGateway,
                                        @org.springframework.beans.factory.annotation.Value("${aimall.payment.provider:SIMULATE}") String provider) {
        this.batchMapper = batchMapper;
        this.itemMapper = itemMapper;
        this.paymentMapper = paymentMapper;
        this.refundMapper = refundMapper;
        this.evidenceMapper = evidenceMapper;
        this.orderMapper = orderMapper;
        this.paymentGateway = paymentGateway;
        this.refundGateway = refundGateway;
        this.provider = provider == null || provider.isBlank() ? "SIMULATE" : provider.trim().toUpperCase();
    }

    @Scheduled(cron = "${aimall.reconciliation.cron:0 20 3 * * *}")
    public void reconcileYesterday() {
        run(LocalDate.now().minusDays(1));
    }

    public Map<String, Object> run(LocalDate date) {
        PaymentReconciliationBatch batch = new PaymentReconciliationBatch();
        batch.setBatchNo(provider + "-" + date + "-" + UUID.randomUUID().toString().substring(0, 8));
        batch.setProvider(provider);
        batch.setReconcileDate(date);
        if (batchMapper.reserve(batch) != 1) {
            PaymentReconciliationBatch existing = batchMapper.selectOne(new LambdaQueryWrapper<PaymentReconciliationBatch>()
                    .eq(PaymentReconciliationBatch::getProvider, provider)
                    .eq(PaymentReconciliationBatch::getReconcileDate, date)
                    .eq(PaymentReconciliationBatch::getStatus, "RUNNING")
                    .orderByDesc(PaymentReconciliationBatch::getId)
                    .last("limit 1"));
            return summary(existing);
        }
        int checked = 0;
        int differences = 0;
        try {
            LocalDateTime start = date.atStartOfDay();
            LocalDateTime end = date.plusDays(1).atStartOfDay();
            List<OmsPaymentRecord> payments = paymentMapper.selectList(new LambdaQueryWrapper<OmsPaymentRecord>()
                    .eq(OmsPaymentRecord::getPayChannel, provider)
                    .ge(OmsPaymentRecord::getCreateTime, start).lt(OmsPaymentRecord::getCreateTime, end));
            for (OmsPaymentRecord payment : payments) {
                checked++;
                List<PaymentCallbackEvent> evidence = evidenceMapper.selectList(
                        new LambdaQueryWrapper<PaymentCallbackEvent>()
                                .eq(PaymentCallbackEvent::getProvider, provider)
                                .eq(PaymentCallbackEvent::getOrderId, payment.getOrderId())
                                .eq(PaymentCallbackEvent::getSignatureValid, 1));
                differences += comparePayment(batch.getId(), payment, evidence, queryPayment(payment));
            }
            List<OmsRefundRecord> refunds = refundMapper.selectList(new LambdaQueryWrapper<OmsRefundRecord>()
                    .eq(OmsRefundRecord::getRefundChannel, provider)
                    .ge(OmsRefundRecord::getCreateTime, start).lt(OmsRefundRecord::getCreateTime, end));
            for (OmsRefundRecord refund : refunds) {
                checked++;
                differences += compareRefund(batch.getId(), refund, queryRefund(refund));
            }
            batchMapper.finish(batch.getId(), "COMPLETED", checked, differences);
        } catch (Exception e) {
            batchMapper.finish(batch.getId(), "FAILED", checked, differences);
            throw e;
        }
        return Map.of("batchId", batch.getId(), "date", date.toString(), "checkedCount", checked,
                "differenceCount", differences, "status", "COMPLETED");
    }

    private int comparePayment(Long batchId, OmsPaymentRecord local, List<PaymentCallbackEvent> evidence,
                               PaymentQueryEvidence query) {
        boolean localPaid = "PAID".equals(local.getPayStatus())
                || "PARTIALLY_REFUNDED".equals(local.getPayStatus())
                || "REFUNDED".equals(local.getPayStatus());
        List<PaymentCallbackEvent> successes = evidence.stream()
                .filter(item -> "TRADE_SUCCESS".equals(item.getProviderStatus())
                        || "TRADE_FINISHED".equals(item.getProviderStatus()))
                .toList();
        PaymentCallbackEvent callbackSuccess = successes.stream().findFirst().orElse(null);
        boolean channelPaid = query == null
                ? callbackSuccess != null
                : "TRADE_SUCCESS".equals(query.status()) || "TRADE_FINISHED".equals(query.status());
        BigDecimal channelAmount = query != null && query.amount() != null
                ? query.amount() : callbackSuccess == null ? null : callbackSuccess.getAmount();
        String channelStatus = query == null
                ? callbackSuccess == null ? "NO_VERIFIED_CHANNEL_EVIDENCE" : callbackSuccess.getProviderStatus()
                : query.status();
        String detail = query == null
                ? callbackSuccess == null ? null : callbackSuccess.getRawPayload()
                : query.raw();
        String autoQueryStatus = query == null ? "FAILED" : "COMPLETED";
        int differences = 0;
        if (successes.size() > 1) {
            record(batchId, local.getOrderId(), null, "DUPLICATE_CALLBACK", local.getPaymentState(),
                    channelStatus, local.getPaidAmount(), channelAmount,
                    "verifiedSuccessCallbacks=" + successes.size(), autoQueryStatus);
            differences++;
        }
        if (channelPaid && isClosed(local.getPaymentState())) {
            record(batchId, local.getOrderId(), null, "LATE_PAYMENT_AFTER_CLOSE", local.getPaymentState(),
                    channelStatus, local.getPaidAmount(), channelAmount, detail, autoQueryStatus);
            return differences + 1;
        }
        BigDecimal expected = local.getAmount() == null ? local.getPaidAmount() : local.getAmount();
        if (channelPaid && !same(expected, channelAmount)) {
            record(batchId, local.getOrderId(), null, "AMOUNT_MISMATCH", local.getPaymentState(),
                    channelStatus, expected, channelAmount, detail, autoQueryStatus);
            differences++;
        }
        if (localPaid && !channelPaid) {
            record(batchId, local.getOrderId(), null, "LOCAL_MISSING_CHANNEL", local.getPaymentState(),
                    channelStatus, local.getPaidAmount(), channelAmount, detail, autoQueryStatus);
            differences++;
        } else if (!localPaid && channelPaid) {
            record(batchId, local.getOrderId(), null, "CHANNEL_MISSING_LOCAL", local.getPaymentState(),
                    channelStatus, local.getPaidAmount(), channelAmount, detail, autoQueryStatus);
            differences++;
        }
        return differences;
    }

    private int compareRefund(Long batchId, OmsRefundRecord local, RefundQueryEvidence query) {
        boolean localSucceeded = "SUCCEEDED".equals(local.getRefundStatus());
        boolean providerSucceeded = query == null
                ? "SUCCEEDED".equals(local.getProviderStatus())
                    && local.getRefundTransactionNo() != null && !local.getRefundTransactionNo().isBlank()
                : "SUCCEEDED".equals(query.status());
        BigDecimal providerAmount = query == null ? local.getAmount() : query.amount();
        if (localSucceeded != providerSucceeded || (providerSucceeded && !same(local.getAmount(), providerAmount))) {
            record(batchId, local.getOrderId(), local.getId(), "REFUND_MISMATCH", local.getRefundStatus(),
                    query == null ? local.getProviderStatus() : query.status(), local.getAmount(), providerAmount,
                    query == null ? local.getFailureReason() : query.raw(), query == null ? "FAILED" : "COMPLETED");
            return 1;
        }
        return 0;
    }

    private void record(Long batchId, Long orderId, Long refundId, String type, String localStatus,
                        String providerStatus, BigDecimal localAmount, BigDecimal providerAmount, String detail,
                        String autoQueryStatus) {
        PaymentReconciliationItem item = new PaymentReconciliationItem();
        item.setBatchId(batchId);
        item.setOrderId(orderId);
        item.setRefundRecordId(refundId);
        item.setDifferenceType(type);
        item.setLocalStatus(localStatus);
        item.setProviderStatus(providerStatus);
        item.setLocalAmount(localAmount);
        item.setProviderAmount(providerAmount);
        item.setDetail(limit(detail));
        item.setResolutionStatus("OPEN");
        item.setAutoQueryStatus(autoQueryStatus);
        itemMapper.insert(item);
        if (refundId != null) refundMapper.markReconciliationOpen(refundId);
        if (orderMapper != null && orderId != null) {
            orderMapper.placeFinancialHold(orderId, "PAYMENT_RECONCILIATION:" + type + ":" + item.getId());
        }
    }

    private PaymentQueryEvidence queryPayment(OmsPaymentRecord payment) {
        if (paymentGateway == null) return null;
        try {
            AlipayPaymentGateway.QueryResult result = paymentGateway.query(payment.getOrderSn());
            return new PaymentQueryEvidence(result.tradeStatus(), result.totalAmount(), limit(result.rawResponse()));
        } catch (Exception exception) {
            return null;
        }
    }

    private RefundQueryEvidence queryRefund(OmsRefundRecord refund) {
        if (refundGateway == null) return null;
        try {
            RefundGateway.QueryRefundResult result = refundGateway.queryRefund(
                    refund.getRequestId(), refund.getOrderSn(), refund.getAmount());
            return new RefundQueryEvidence(result.status(), result.refundAmount(), limit(result.rawResponse()));
        } catch (Exception exception) {
            return null;
        }
    }

    private boolean isClosed(String state) {
        return "CLOSED".equals(state) || "CLOSING".equals(state) || "CLOSE_UNKNOWN".equals(state);
    }

    private Map<String, Object> summary(PaymentReconciliationBatch batch) {
        if (batch == null) return Map.of("status", "NOT_FOUND");
        return Map.of("batchId", batch.getId(), "date", batch.getReconcileDate().toString(),
                "checkedCount", batch.getCheckedCount(), "differenceCount", batch.getDifferenceCount(),
                "status", batch.getStatus());
    }

    private boolean same(BigDecimal left, BigDecimal right) { return left != null && right != null && left.compareTo(right) == 0; }
    private String limit(String value) { return value == null ? null : value.substring(0, Math.min(1000, value.length())); }

    private record PaymentQueryEvidence(String status, BigDecimal amount, String raw) {}
    private record RefundQueryEvidence(String status, BigDecimal amount, String raw) {}
}
