package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsOrder;
import com.aimall.server.entity.OmsOrderItem;
import com.aimall.server.mapper.OmsOrderItemMapper;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.service.InventoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OrderTimeoutCloser {

    private static final String INVENTORY_NOT_RESERVED = "NOT_RESERVED";
    private static final String INVENTORY_RESERVED = "RESERVED";
    private static final String INVENTORY_RELEASED = "RELEASED";

    private final OmsOrderMapper orderMapper;
    private final OmsOrderItemMapper orderItemMapper;
    private final InventoryService inventoryService;
    private final CouponServiceImpl couponService;

    public OrderTimeoutCloser(
            OmsOrderMapper orderMapper,
            OmsOrderItemMapper orderItemMapper,
            InventoryService inventoryService,
            CouponServiceImpl couponService
    ) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.inventoryService = inventoryService;
        this.couponService = couponService;
    }

    @Transactional
    public boolean closeAfterChannelConfirmed(Long orderId, LocalDateTime closeTime) {
        OmsOrder order = orderMapper.selectById(orderId);
        if (order == null || order.getStatus() == null || order.getStatus() != 0) {
            return false;
        }
        List<OmsOrderItem> orderItems = orderItemMapper.selectList(
                new LambdaQueryWrapper<OmsOrderItem>().eq(OmsOrderItem::getOrderId, orderId)
        );
        String inventoryStatus = order.getInventoryReservationStatus() == null
                ? INVENTORY_NOT_RESERVED : order.getInventoryReservationStatus();
        String targetInventoryStatus = inventoryStatus;
        if (INVENTORY_RESERVED.equals(inventoryStatus)) {
            inventoryService.releaseForOrderItems(orderItems);
            targetInventoryStatus = INVENTORY_RELEASED;
        }
        couponService.releaseCouponUsage(order.getMemberId(), orderId);
        if (orderMapper.closeExpired(orderId, inventoryStatus, targetInventoryStatus, closeTime) != 1) {
            throw new RuntimeException("订单状态已变化，超时关单失败");
        }
        return true;
    }

    @Deprecated
    public boolean close(Long orderId, LocalDateTime closeTime) {
        return closeAfterChannelConfirmed(orderId, closeTime);
    }
}
