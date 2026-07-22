package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsOrder;
import com.aimall.server.entity.OmsPaymentRecord;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.mapper.OmsPaymentRecordMapper;
import com.aimall.server.payment.AlipayPaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;

@Service
public class PaymentQueryService {
    private static final Logger log = LoggerFactory.getLogger(PaymentQueryService.class);
    private final OmsPaymentRecordMapper paymentMapper;
    private final OmsOrderMapper orderMapper;
    private final AlipayPaymentGateway gateway;
    private final PaymentQueryStateService stateService;
    private final OutboxEventService outboxEventService;
    private final PaymentEvidenceService paymentEvidenceService;

    public PaymentQueryService(OmsPaymentRecordMapper paymentMapper, OmsOrderMapper orderMapper,
                               AlipayPaymentGateway gateway, PaymentQueryStateService stateService) {
        this(paymentMapper, orderMapper, gateway, stateService, null, null);
    }

    public PaymentQueryService(OmsPaymentRecordMapper paymentMapper, OmsOrderMapper orderMapper,
                               AlipayPaymentGateway gateway, PaymentQueryStateService stateService,
                               OutboxEventService outboxEventService) {
        this(paymentMapper, orderMapper, gateway, stateService, outboxEventService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentQueryService(OmsPaymentRecordMapper paymentMapper, OmsOrderMapper orderMapper,
                               AlipayPaymentGateway gateway, PaymentQueryStateService stateService,
                               OutboxEventService outboxEventService,
                               PaymentEvidenceService paymentEvidenceService) {
        this.paymentMapper = paymentMapper;
        this.orderMapper = orderMapper;
        this.gateway = gateway;
        this.stateService = stateService;
        this.outboxEventService = outboxEventService;
        this.paymentEvidenceService = paymentEvidenceService;
    }

    public Map<String, Object> queryOwned(Long orderId, Long memberId) {
        OmsOrder order = orderMapper.selectById(orderId);
        if (order == null || !memberId.equals(order.getMemberId())) throw new RuntimeException("订单不存在");
        OmsPaymentRecord payment = paymentMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OmsPaymentRecord>()
                .eq(OmsPaymentRecord::getOrderId, orderId).last("limit 1"));
        if (payment == null) throw new RuntimeException("支付单不存在");
        process(payment);
        OmsPaymentRecord updated = paymentMapper.selectById(payment.getId());
        return Map.of("orderId", orderId, "paymentState", updated.getPaymentState(),
                "payStatus", updated.getPayStatus(), "transactionNo", value(updated.getTransactionNo()));
    }

    @Scheduled(fixedDelayString = "${aimall.payment.query-scan-ms:15000}")
    public void recoverPayments() {
        for (OmsPaymentRecord payment : paymentMapper.listQueryCandidates(30)) {
            if (outboxEventService == null) {
                processSafely(payment);
                continue;
            }
            String bucket = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
            outboxEventService.enqueue("PAYMENT", String.valueOf(payment.getId()), "PAYMENT_QUERY_REQUESTED",
                    "PAYMENT_QUERY:" + payment.getId() + ":" + bucket, Map.of("paymentId", payment.getId()));
        }
    }

    public void processById(Long paymentId) {
        OmsPaymentRecord payment = paymentMapper.selectById(paymentId);
        if (payment != null) process(payment);
    }

    public void process(OmsPaymentRecord payment) {
        if (paymentMapper.claimQuery(payment.getId()) != 1) return;
        try {
            AlipayPaymentGateway.QueryResult result = gateway.query(payment.getOrderSn());
            if (paymentEvidenceService != null) paymentEvidenceService.recordQuery(payment, result);
            stateService.apply(payment.getId(), result);
        } catch (Exception e) {
            stateService.markUnknown(payment.getId(), e.getMessage());
            throw e;
        }
    }

    private void processSafely(OmsPaymentRecord payment) {
        try { process(payment); }
        catch (Exception e) { log.warn("Payment query failed, paymentId={}", payment.getId(), e); }
    }

    private String value(String value) { return value == null ? "" : value; }
}
