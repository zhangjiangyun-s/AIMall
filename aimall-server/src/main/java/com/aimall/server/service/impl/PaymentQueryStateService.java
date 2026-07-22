package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsOrder;
import com.aimall.server.entity.OmsOrderItem;
import com.aimall.server.entity.OmsPaymentRecord;
import com.aimall.server.mapper.OmsOrderItemMapper;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.mapper.OmsPaymentRecordMapper;
import com.aimall.server.payment.AlipayPaymentGateway;
import com.aimall.server.outbox.OutboxEventType;
import com.aimall.server.service.InventoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.dao.PessimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class PaymentQueryStateService {
    private final OmsPaymentRecordMapper paymentMapper;
    private final OmsOrderMapper orderMapper;
    private final OmsOrderItemMapper orderItemMapper;
    private final InventoryService inventoryService;
    private final OutboxEventService outboxEventService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MoneySnapshotService moneySnapshotService;

    public PaymentQueryStateService(OmsPaymentRecordMapper paymentMapper, OmsOrderMapper orderMapper,
                                    OmsOrderItemMapper orderItemMapper, InventoryService inventoryService) {
        this(paymentMapper, orderMapper, orderItemMapper, inventoryService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentQueryStateService(OmsPaymentRecordMapper paymentMapper, OmsOrderMapper orderMapper,
                                    OmsOrderItemMapper orderItemMapper, InventoryService inventoryService,
                                    OutboxEventService outboxEventService) {
        this.paymentMapper = paymentMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.inventoryService = inventoryService;
        this.outboxEventService = outboxEventService;
    }

    @Transactional
    @Retryable(retryFor = PessimisticLockingFailureException.class, maxAttempts = 3,
            backoff = @Backoff(delay = 50, maxDelay = 500, multiplier = 2))
    public void apply(Long paymentId, AlipayPaymentGateway.QueryResult result) {
        OmsPaymentRecord payment = paymentMapper.selectById(paymentId);
        if (payment == null || !"QUERYING".equals(payment.getPaymentState())) return;
        if ("CLOSED".equals(payment.getPayStatus())
                && ("TRADE_CLOSED".equals(result.tradeStatus())
                    || "NOT_FOUND".equals(result.tradeStatus())
                    || "WAIT_BUYER_PAY".equals(result.tradeStatus()))) {
            requireUpdated(paymentMapper.markQueryClosed(paymentId, result.rawResponse()));
            return;
        }
        switch (result.tradeStatus()) {
            case "TRADE_SUCCESS", "TRADE_FINISHED" -> settlePaid(payment, result);
            case "WAIT_BUYER_PAY", "NOT_FOUND" -> requireUpdated(paymentMapper.markQueryWaiting(paymentId));
            case "TRADE_CLOSED" -> {
                requireUpdated(paymentMapper.markQueryClosed(paymentId, result.rawResponse()));
                enqueuePaymentOutcome(payment, OutboxEventType.PAYMENT_FAILED, "TRADE_CLOSED");
            }
            default -> {
                requireUpdated(paymentMapper.markQueryUnknown(paymentId, result.rawResponse()));
                enqueuePaymentOutcome(payment, OutboxEventType.PAYMENT_UNKNOWN, result.tradeStatus());
            }
        }
    }

    @Transactional
    public void markUnknown(Long paymentId, String message) {
        OmsPaymentRecord payment = paymentMapper.selectById(paymentId);
        if (paymentMapper.markQueryUnknown(paymentId, message == null ? "query failed" : message) == 1
                && payment != null) {
            enqueuePaymentOutcome(payment, OutboxEventType.PAYMENT_UNKNOWN, "QUERY_ERROR");
        }
    }

    private void settlePaid(OmsPaymentRecord payment, AlipayPaymentGateway.QueryResult result) {
        OmsOrder order = orderMapper.selectById(payment.getOrderId());
        if (order == null) throw new IllegalStateException("查单对应订单不存在");
        BigDecimal expected = order.getPayAmount() == null ? BigDecimal.ZERO : order.getPayAmount();
        if (result.totalAmount() == null || result.totalAmount().compareTo(expected) != 0
                || payment.getAmount() == null || payment.getAmount().compareTo(expected) != 0) {
            paymentMapper.markQueryUnknown(payment.getId(), "AMOUNT_MISMATCH:" + result.rawResponse());
            return;
        }
        if (order.getStatus() != null && order.getStatus() == 1 && "PAID".equals(payment.getPayStatus())) {
            requireUpdated(paymentMapper.markAlipayPaid(order.getId(), expected, result.tradeNo(),
                    LocalDateTime.now(), result.rawResponse()));
            recordMoneySnapshots(order.getId(), payment.getId());
            enqueuePaymentSucceeded(payment.getId(), order.getId(), result.tradeNo());
            return;
        }
        if (order.getStatus() == null || order.getStatus() != 0 || !"RESERVED".equals(order.getInventoryReservationStatus())) {
            paymentMapper.markQueryUnknown(payment.getId(), "LATE_PAYMENT_AFTER_CLOSE:" + result.rawResponse());
            return;
        }
        List<OmsOrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OmsOrderItem>()
                .eq(OmsOrderItem::getOrderId, order.getId()));
        inventoryService.deductForOrderItems(items);
        LocalDateTime now = LocalDateTime.now();
        requireUpdated(orderMapper.markPaid(order.getId(), order.getMemberId(), 1, now));
        requireUpdated(paymentMapper.markAlipayPaid(order.getId(), expected, result.tradeNo(), now, result.rawResponse()));
        recordMoneySnapshots(order.getId(), payment.getId());
        enqueuePaymentSucceeded(payment.getId(), order.getId(), result.tradeNo());
    }

    private void requireUpdated(int updated) {
        if (updated != 1) throw new IllegalStateException("支付查单状态已被并发修改");
    }

    private void enqueuePaymentSucceeded(Long paymentId, Long orderId, String transactionNo) {
        if (outboxEventService != null) {
            outboxEventService.enqueue("PAYMENT", String.valueOf(paymentId), 2L,
                    OutboxEventType.PAYMENT_SUCCEEDED, "PAYMENT_SUCCEEDED:" + paymentId, 1,
                    java.util.Map.of("paymentId", paymentId, "orderId", orderId,
                            "transactionNo", transactionNo == null ? "" : transactionNo));
        }
    }

    private void enqueuePaymentOutcome(OmsPaymentRecord payment, OutboxEventType type, String reason) {
        if (outboxEventService == null) return;
        outboxEventService.enqueue("PAYMENT", String.valueOf(payment.getId()), 2L, type,
                type.value().toUpperCase() + ":" + payment.getId(), 1,
                java.util.Map.of("paymentId", payment.getId(), "orderId", payment.getOrderId(),
                        "reason", reason == null ? "UNKNOWN" : reason));
    }

    private void recordMoneySnapshots(Long orderId, Long paymentId) {
        if (moneySnapshotService == null) return;
        OmsOrder currentOrder = orderMapper.selectById(orderId);
        OmsPaymentRecord currentPayment = paymentMapper.selectById(paymentId);
        if (currentOrder != null) moneySnapshotService.record(currentOrder);
        if (currentPayment != null) moneySnapshotService.record(currentPayment);
    }
}
