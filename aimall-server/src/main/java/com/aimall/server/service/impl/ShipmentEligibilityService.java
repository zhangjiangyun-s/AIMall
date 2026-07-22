package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsOrder;
import com.aimall.server.entity.OmsOrderItem;
import com.aimall.server.entity.OmsPaymentRecord;
import com.aimall.server.mapper.OmsOrderItemMapper;
import com.aimall.server.mapper.OmsPaymentRecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ShipmentEligibilityService {
    private final OmsPaymentRecordMapper paymentMapper;
    private final OmsOrderItemMapper orderItemMapper;

    public ShipmentEligibilityService(OmsPaymentRecordMapper paymentMapper, OmsOrderItemMapper orderItemMapper) {
        this.paymentMapper = paymentMapper;
        this.orderItemMapper = orderItemMapper;
    }

    public void assertEligible(OmsOrder order, Map<Long, Integer> requestedQuantities) {
        if (order == null || Integer.valueOf(1).equals(order.getDeleteStatus()) || order.getStatus() == null
                || (order.getStatus() != 1 && order.getStatus() != 2)) {
            throw new RuntimeException("当前订单不可发货");
        }
        if (Integer.valueOf(1).equals(order.getFinancialHold())) {
            throw new RuntimeException("订单存在资金对账或风险冻结，已阻止发货");
        }
        if (order.getRefundStatus() != null && !"NONE".equals(order.getRefundStatus())) {
            throw new RuntimeException("订单已进入退款流程，禁止发货");
        }

        OmsPaymentRecord payment = paymentMapper.selectOne(
                new LambdaQueryWrapper<OmsPaymentRecord>()
                        .eq(OmsPaymentRecord::getOrderId, order.getId())
                        .orderByDesc(OmsPaymentRecord::getId)
                        .last("limit 1")
        );
        if (payment == null || !"PAID".equals(payment.getPayStatus())
                || !"PAID".equals(payment.getPaymentState())) {
            throw new RuntimeException("支付状态未达到可发货条件");
        }
        if (!same(payment.getPaidAmount(), order.getPayAmount())) {
            throw new RuntimeException("订单未完成全额支付，禁止发货");
        }
        if (value(payment.getRefundedAmount()).signum() != 0) {
            throw new RuntimeException("支付单已发生退款，禁止发货");
        }
        if (requestedQuantities == null || requestedQuantities.isEmpty()) {
            throw new RuntimeException("没有可发货的订单商品");
        }

        List<OmsOrderItem> selected = orderItemMapper.selectList(
                new LambdaQueryWrapper<OmsOrderItem>()
                        .eq(OmsOrderItem::getOrderId, order.getId())
                        .in(OmsOrderItem::getId, requestedQuantities.keySet())
        );
        Map<Long, OmsOrderItem> byId = selected.stream()
                .collect(Collectors.toMap(OmsOrderItem::getId, Function.identity()));
        if (byId.size() != requestedQuantities.size()) {
            throw new RuntimeException("发货请求包含不属于当前订单的商品");
        }
        for (Map.Entry<Long, Integer> entry : requestedQuantities.entrySet()) {
            OmsOrderItem item = byId.get(entry.getKey());
            int requested = entry.getValue() == null ? 0 : entry.getValue();
            int remaining = safe(item.getProductQuantity())
                    - safe(item.getShippedQuantity())
                    - safe(item.getReturnReservedQuantity())
                    - safe(item.getRefundedQuantity());
            if (requested <= 0 || requested > remaining) {
                throw new RuntimeException("订单项发货数量超过剩余可售数量");
            }
        }
    }

    private boolean same(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private BigDecimal value(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private int safe(Integer quantity) {
        return quantity == null ? 0 : quantity;
    }
}
