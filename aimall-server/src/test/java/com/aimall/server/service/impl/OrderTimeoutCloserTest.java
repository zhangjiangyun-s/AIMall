package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsOrder;
import com.aimall.server.mapper.OmsOrderItemMapper;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.service.InventoryService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderTimeoutCloserTest {

    @Test
    void closesExpiredOrderAndReleasesReservedResources() {
        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        OmsOrderItemMapper itemMapper = mock(OmsOrderItemMapper.class);
        InventoryService inventoryService = mock(InventoryService.class);
        CouponServiceImpl couponService = mock(CouponServiceImpl.class);
        OrderTimeoutCloser closer = new OrderTimeoutCloser(orderMapper, itemMapper, inventoryService, couponService);
        OmsOrder order = waitPayOrder();
        order.setInventoryReservationStatus("RESERVED");
        when(orderMapper.selectById(100L)).thenReturn(order);
        when(itemMapper.selectList(any())).thenReturn(List.of());
        LocalDateTime closeTime = LocalDateTime.now();
        when(orderMapper.closeExpired(100L, "RESERVED", "RELEASED", closeTime)).thenReturn(1);

        assertTrue(closer.close(100L, closeTime));

        verify(inventoryService).releaseForOrderItems(List.of());
        verify(couponService).releaseCouponUsage(1L, 100L);
    }

    @Test
    void throwsWhenOrderStatusCasLosesRace() {
        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        OmsOrderItemMapper itemMapper = mock(OmsOrderItemMapper.class);
        InventoryService inventoryService = mock(InventoryService.class);
        CouponServiceImpl couponService = mock(CouponServiceImpl.class);
        OrderTimeoutCloser closer = new OrderTimeoutCloser(orderMapper, itemMapper, inventoryService, couponService);
        when(orderMapper.selectById(100L)).thenReturn(waitPayOrder());
        when(itemMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.closeExpired(any(), any(), any(), any())).thenReturn(0);

        assertThrows(RuntimeException.class, () -> closer.close(100L, LocalDateTime.now()));
    }

    @Test
    void closesHistoricalOrderWithoutReleasingUntrackedInventory() {
        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        OmsOrderItemMapper itemMapper = mock(OmsOrderItemMapper.class);
        InventoryService inventoryService = mock(InventoryService.class);
        CouponServiceImpl couponService = mock(CouponServiceImpl.class);
        OrderTimeoutCloser closer = new OrderTimeoutCloser(orderMapper, itemMapper, inventoryService, couponService);
        OmsOrder order = waitPayOrder();
        order.setInventoryReservationStatus("NOT_RESERVED");
        when(orderMapper.selectById(100L)).thenReturn(order);
        when(itemMapper.selectList(any())).thenReturn(List.of());
        LocalDateTime closeTime = LocalDateTime.now();
        when(orderMapper.closeExpired(100L, "NOT_RESERVED", "NOT_RESERVED", closeTime)).thenReturn(1);

        assertTrue(closer.close(100L, closeTime));

        verify(inventoryService, never()).releaseForOrderItems(any());
    }

    private OmsOrder waitPayOrder() {
        OmsOrder order = new OmsOrder();
        order.setId(100L);
        order.setMemberId(1L);
        order.setStatus(0);
        return order;
    }
}
