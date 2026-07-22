package com.aimall.server.service.impl;

import com.aimall.server.entity.LatePaymentCase;
import com.aimall.server.entity.OmsOrder;
import com.aimall.server.entity.OmsPaymentRecord;
import com.aimall.server.mapper.LatePaymentCaseMapper;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.mapper.OmsPaymentRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Service
public class LatePaymentStateService {
    private final LatePaymentCaseMapper caseMapper;
    private final OmsPaymentRecordMapper paymentMapper;
    private final OmsOrderMapper orderMapper;
    private final OutboxEventService outboxEventService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MoneySnapshotService moneySnapshotService;

    public LatePaymentStateService(LatePaymentCaseMapper caseMapper, OmsPaymentRecordMapper paymentMapper,
                                   OmsOrderMapper orderMapper, OutboxEventService outboxEventService) {
        this.caseMapper = caseMapper;
        this.paymentMapper = paymentMapper;
        this.orderMapper = orderMapper;
        this.outboxEventService = outboxEventService;
    }

    @Transactional
    public void recordDetected(OmsOrder order, OmsPaymentRecord payment, String tradeNo,
                               BigDecimal amount, String rawCallback) {
        if (paymentMapper.markLatePayment(payment.getId(), amount, tradeNo, LocalDateTime.now(), rawCallback) != 1) {
            OmsPaymentRecord current = paymentMapper.selectById(payment.getId());
            if (current == null || !("LATE_PAYMENT".equals(current.getPaymentState())
                    || "LATE_REFUNDED".equals(current.getPaymentState()))) {
                throw new IllegalStateException("晚到支付状态持久化失败");
            }
        }
        recordMoneySnapshots(order.getId(), payment.getId());
        LatePaymentCase value = new LatePaymentCase();
        value.setPaymentId(payment.getId());
        value.setOrderId(order.getId());
        value.setOrderSn(order.getOrderSn());
        value.setAmount(amount);
        value.setProviderTradeNo(tradeNo);
        value.setRefundRequestId("LATE-" + payment.getId());
        caseMapper.reserve(value);
        LatePaymentCase current = value.getId() == null ? caseMapper.findByPaymentId(payment.getId()) : value;
        if (current == null) throw new IllegalStateException("晚到支付案件创建失败");
        outboxEventService.enqueue("LATE_PAYMENT", String.valueOf(current.getId()),
                "LATE_PAYMENT_REFUND_REQUESTED", "LATE_PAYMENT_REFUND:" + current.getId(),
                Map.of("latePaymentCaseId", current.getId()));
    }

    @Transactional
    public void finalizeRefund(LatePaymentCase value, String refundTradeNo) {
        if (caseMapper.markRefunded(value.getId(), refundTradeNo) != 1) {
            LatePaymentCase current = caseMapper.selectById(value.getId());
            if (current != null && "REFUNDED".equals(current.getStatus())) return;
            throw new IllegalStateException("晚到支付退款案件完成失败");
        }
        if (paymentMapper.markLatePaymentRefunded(value.getPaymentId()) != 1) {
            OmsPaymentRecord payment = paymentMapper.selectById(value.getPaymentId());
            if (payment == null || !"LATE_REFUNDED".equals(payment.getPaymentState())) {
                throw new IllegalStateException("晚到支付流水退款状态更新失败");
            }
        }
        orderMapper.markLatePaymentRefunded(value.getOrderId());
        recordMoneySnapshots(value.getOrderId(), value.getPaymentId());
    }

    private void recordMoneySnapshots(Long orderId, Long paymentId) {
        if (moneySnapshotService == null) return;
        OmsOrder currentOrder = orderMapper.selectById(orderId);
        OmsPaymentRecord currentPayment = paymentMapper.selectById(paymentId);
        if (currentOrder != null) moneySnapshotService.record(currentOrder);
        if (currentPayment != null) moneySnapshotService.record(currentPayment);
    }
}
