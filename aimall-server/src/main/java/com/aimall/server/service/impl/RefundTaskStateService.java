package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsOrder;
import com.aimall.server.entity.OmsOrderItem;
import com.aimall.server.entity.OmsOrderReturnApply;
import com.aimall.server.entity.OmsRefundRecord;
import com.aimall.server.entity.OmsOrderReturnItem;
import com.aimall.server.entity.OmsPaymentRecord;
import com.aimall.server.mapper.OmsOrderItemMapper;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.mapper.OmsOrderReturnApplyMapper;
import com.aimall.server.mapper.OmsPaymentRecordMapper;
import com.aimall.server.mapper.OmsRefundRecordMapper;
import com.aimall.server.mapper.OmsOrderReturnItemMapper;
import com.aimall.server.service.InventoryService;
import com.aimall.server.outbox.OutboxEventType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.dao.PessimisticLockingFailureException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RefundTaskStateService {

    private final OmsRefundRecordMapper refundRecordMapper;
    private final OmsOrderReturnApplyMapper returnApplyMapper;
    private final OmsOrderMapper orderMapper;
    private final OmsOrderItemMapper orderItemMapper;
    private final OmsPaymentRecordMapper paymentRecordMapper;
    private final InventoryService inventoryService;
    private final CouponServiceImpl couponService;
    private final OmsOrderReturnItemMapper returnItemMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OutboxEventService outboxEventService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MoneySnapshotService moneySnapshotService;

    public RefundTaskStateService(
            OmsRefundRecordMapper refundRecordMapper,
            OmsOrderReturnApplyMapper returnApplyMapper,
            OmsOrderMapper orderMapper,
            OmsOrderItemMapper orderItemMapper,
            OmsPaymentRecordMapper paymentRecordMapper,
            InventoryService inventoryService,
            CouponServiceImpl couponService,
            OmsOrderReturnItemMapper returnItemMapper
    ) {
        this.refundRecordMapper = refundRecordMapper;
        this.returnApplyMapper = returnApplyMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.paymentRecordMapper = paymentRecordMapper;
        this.inventoryService = inventoryService;
        this.couponService = couponService;
        this.returnItemMapper = returnItemMapper;
    }

    public OmsRefundRecord get(Long id) {
        return refundRecordMapper.selectById(id);
    }

    public List<OmsRefundRecord> listRecoverable(int limit) {
        return refundRecordMapper.listRecoverable(Math.max(1, Math.min(limit, 100)));
    }

    @Transactional
    public boolean claimChannelCall(Long id) {
        return refundRecordMapper.claimChannelCall(id) == 1;
    }

    @Transactional
    public void recordChannelSuccess(Long id, String transactionNo) {
        if (refundRecordMapper.markChannelSucceeded(id, transactionNo) != 1) {
            throw new RuntimeException("退款渠道结果持久化失败");
        }
    }

    @Transactional
    public void recordChannelFailure(Long id, String message) {
        String safeMessage = message == null || message.isBlank() ? "退款渠道调用失败" : message.substring(0, Math.min(500, message.length()));
        if (refundRecordMapper.markChannelFailed(id, safeMessage, LocalDateTime.now().plusSeconds(30)) != 1) {
            throw new RuntimeException("退款失败状态持久化失败");
        }
    }

    @Transactional
    public void recordChannelUnknown(Long id, String message) {
        String safe = safeMessage(message, "退款渠道结果未知");
        if (refundRecordMapper.markChannelUnknown(id, safe, LocalDateTime.now().plusSeconds(15)) != 1) {
            throw new RuntimeException("退款未知状态持久化失败");
        }
    }

    @Transactional
    public boolean claimRefundQuery(Long id) {
        return refundRecordMapper.claimRefundQuery(id) == 1;
    }

    @Transactional
    public void recordRefundQuerySuccess(Long id, String transactionNo) {
        if (refundRecordMapper.markRefundQuerySucceeded(id, transactionNo) != 1) {
            throw new RuntimeException("退款查单成功状态持久化失败");
        }
    }

    @Transactional
    public void recordRefundQueryNotFound(Long id) {
        if (refundRecordMapper.markRefundQueryNotFound(id, LocalDateTime.now().plusSeconds(15)) != 1) {
            throw new RuntimeException("退款查单不存在状态持久化失败");
        }
    }

    @Transactional
    public void recordRefundQueryUnknown(Long id, String providerStatus, String message) {
        if (refundRecordMapper.markRefundQueryUnknown(id, providerStatus, safeMessage(message, "退款查单结果未知"),
                LocalDateTime.now().plusSeconds(30)) != 1) {
            throw new RuntimeException("退款查单未知状态持久化失败");
        }
    }

    @Transactional
    @Retryable(retryFor = PessimisticLockingFailureException.class, maxAttempts = 3,
            backoff = @Backoff(delay = 50, maxDelay = 500, multiplier = 2))
    public void finalizeBusiness(Long refundRecordId) {
        OmsRefundRecord record = refundRecordMapper.selectByIdForUpdate(refundRecordId);
        if (record == null || "SUCCEEDED".equals(record.getRefundStatus())) {
            return;
        }
        if (!"CHANNEL_SUCCEEDED".equals(record.getRefundStatus())) {
            throw new RuntimeException("退款渠道尚未成功，不能完成本地退款");
        }
        OmsOrderReturnApply apply = returnApplyMapper.selectById(record.getReturnApplyId());
        if (apply == null || apply.getStatus() == null || apply.getStatus() != 5) {
            throw new RuntimeException("售后申请不在退款处理中状态");
        }
        OmsOrder order = orderMapper.selectById(record.getOrderId());
        if (order == null || order.getDeleteStatus() == 1 || !order.getMemberId().equals(apply.getMemberId())) {
            throw new RuntimeException("退款订单不存在");
        }
        List<OmsOrderItem> orderItems = orderItemMapper.selectList(
                new LambdaQueryWrapper<OmsOrderItem>().eq(OmsOrderItem::getOrderId, order.getId())
        );
        Map<Long, OmsOrderItem> orderItemById = orderItems.stream()
                .collect(Collectors.toMap(OmsOrderItem::getId, Function.identity()));
        List<OmsOrderReturnItem> returnItems = returnItemMapper.selectList(
                new LambdaQueryWrapper<OmsOrderReturnItem>()
                        .eq(OmsOrderReturnItem::getReturnApplyId, apply.getId())
        );
        if (returnItems.isEmpty()) {
            throw new RuntimeException("退款申请缺少订单项明细");
        }
        List<OmsOrderItem> inventoryItems = returnItems.stream().map(returnItem -> {
            OmsOrderItem source = orderItemById.get(returnItem.getOrderItemId());
            if (source == null) {
                throw new RuntimeException("退款订单项不存在");
            }
            OmsOrderItem item = new OmsOrderItem();
            item.setId(source.getId());
            item.setOrderId(source.getOrderId());
            item.setProductId(source.getProductId());
            item.setProductSkuId(source.getProductSkuId());
            item.setProductQuantity(returnItem.getQuantity());
            return item;
        }).toList();
        inventoryService.restoreForOrderItems(inventoryItems, "REFUND-" + record.getId());
        for (OmsOrderReturnItem returnItem : returnItems) {
            if (orderItemMapper.finalizeReturnQuantity(returnItem.getOrderItemId(), returnItem.getQuantity()) != 1) {
                throw new RuntimeException("订单项已退款数量更新失败");
            }
        }
        if (paymentRecordMapper.addRefund(order.getId(), record.getAmount()) != 1) {
            throw new RuntimeException("支付流水退款状态更新失败");
        }
        if (orderMapper.addRefund(order.getId(), record.getAmount(), LocalDateTime.now()) != 1) {
            throw new RuntimeException("订单退款状态更新失败");
        }
        OmsOrder refundedOrder = orderMapper.selectById(order.getId());
        if (moneySnapshotService != null) {
            OmsPaymentRecord refundedPayment = paymentRecordMapper.selectOne(
                    new LambdaQueryWrapper<OmsPaymentRecord>()
                            .eq(OmsPaymentRecord::getOrderId, order.getId())
                            .last("limit 1")
            );
            if (refundedOrder != null) moneySnapshotService.record(refundedOrder);
            if (refundedPayment != null) moneySnapshotService.record(refundedPayment);
        }
        if (refundedOrder != null && "FULL_REFUNDED".equals(refundedOrder.getRefundStatus())) {
            couponService.releaseCouponAfterRefund(order.getMemberId(), order.getId());
        }
        if (returnApplyMapper.transition(apply.getId(), 5, 3, record.getHandleNote()) != 1) {
            throw new RuntimeException("售后退款状态更新失败");
        }
        if (refundRecordMapper.markBusinessSucceeded(record.getId()) != 1) {
            throw new RuntimeException("退款流水完成状态更新失败");
        }
        if (outboxEventService != null) {
            outboxEventService.enqueue("REFUND", String.valueOf(record.getId()), 2L,
                    OutboxEventType.REFUND_SUCCEEDED, "REFUND_SUCCEEDED:" + record.getId(), 1,
                    Map.of("refundRecordId", record.getId(), "orderId", order.getId(),
                            "returnApplyId", apply.getId(), "amount", record.getAmount()));
        }
    }

    private String safeMessage(String message, String fallback) {
        String value = message == null || message.isBlank() ? fallback : message;
        return value.substring(0, Math.min(500, value.length()));
    }
}
