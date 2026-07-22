package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsOrder;
import com.aimall.server.entity.OmsOrderRequest;
import com.aimall.server.mapper.OmsCartItemMapper;
import com.aimall.server.mapper.OmsOrderItemMapper;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.mapper.OmsOrderRequestMapper;
import com.aimall.server.mapper.OmsPaymentRecordMapper;
import com.aimall.server.mapper.UmsMemberAddressMapper;
import com.aimall.server.mapper.UmsMemberMapper;
import com.aimall.server.order.OrderStatus;
import com.aimall.server.service.CartPricingService;
import com.aimall.server.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceImplTest {

    private OmsOrderMapper orderMapper;
    private OmsOrderItemMapper orderItemMapper;
    private OmsOrderRequestMapper orderRequestMapper;
    private InventoryService inventoryService;
    private CouponServiceImpl couponService;
    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OmsOrderMapper.class);
        orderItemMapper = mock(OmsOrderItemMapper.class);
        orderRequestMapper = mock(OmsOrderRequestMapper.class);
        inventoryService = mock(InventoryService.class);
        couponService = mock(CouponServiceImpl.class);
        orderService = new OrderServiceImpl(
                orderMapper,
                orderItemMapper,
                mock(OmsCartItemMapper.class),
                inventoryService,
                mock(UmsMemberMapper.class),
                mock(UmsMemberAddressMapper.class),
                couponService,
                mock(CartPricingService.class),
                orderRequestMapper,
                mock(OmsPaymentRecordMapper.class),
                30
        );
    }

    @Test
    void duplicateSucceededRequestReturnsOriginalOrder() {
        OmsOrderRequest request = new OmsOrderRequest();
        request.setMemberId(1L);
        request.setRequestId("request-1");
        request.setStatus("SUCCEEDED");
        request.setOrderId(100L);
        OmsOrder order = ownedOrder(100L, 1L, OrderStatus.WAIT_PAY.code());
        when(orderRequestMapper.reserve(1L, "request-1")).thenReturn(0);
        when(orderRequestMapper.selectOne(any())).thenReturn(request);
        when(orderMapper.selectById(100L)).thenReturn(order);

        OmsOrder result = orderService.create(1L, "request-1", 10L, null, List.of(20L));

        assertSame(order, result);
    }

    @Test
    void cancelFailsWhenCasTransitionLosesRace() {
        OmsOrder order = ownedOrder(100L, 1L, OrderStatus.WAIT_PAY.code());
        order.setInventoryReservationStatus("RESERVED");
        when(orderMapper.selectById(100L)).thenReturn(order);
        when(orderItemMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.transitionOwnedStatusAndInventory(
                eq(100L),
                eq(1L),
                eq(OrderStatus.WAIT_PAY.code()),
                eq(OrderStatus.CLOSED.code()),
                eq("RESERVED"),
                eq("RELEASED"),
                any()
        )).thenReturn(0);

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> orderService.cancel(1L, 100L)
        );

        assertEquals("订单状态已变化，取消失败", exception.getMessage());
        verify(inventoryService).releaseForOrderItems(List.of());
    }

    @Test
    void cancelHistoricalOrderWithoutReservationDoesNotReleaseAggregateStock() {
        OmsOrder order = ownedOrder(101L, 1L, OrderStatus.WAIT_PAY.code());
        order.setInventoryReservationStatus("NOT_RESERVED");
        when(orderMapper.selectById(101L)).thenReturn(order);
        when(orderItemMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.transitionOwnedStatusAndInventory(
                eq(101L), eq(1L), eq(OrderStatus.WAIT_PAY.code()), eq(OrderStatus.CLOSED.code()),
                eq("NOT_RESERVED"), eq("NOT_RESERVED"), any()
        )).thenReturn(1);

        orderService.cancel(1L, 101L);

        verify(inventoryService, never()).releaseForOrderItems(any());
    }

    @Test
    void userCancellationPersistsCloseIntentAndFallsBackToOutboxWhenChannelIsUncertain() {
        PaymentCloseService paymentCloseService = mock(PaymentCloseService.class);
        OutboxEventService outboxEventService = mock(OutboxEventService.class);
        OrderServiceImpl channelAwareService = new OrderServiceImpl(
                orderMapper, orderItemMapper, mock(OmsCartItemMapper.class), inventoryService,
                mock(UmsMemberMapper.class), mock(UmsMemberAddressMapper.class), couponService,
                mock(CartPricingService.class), orderRequestMapper, mock(OmsPaymentRecordMapper.class),
                30, paymentCloseService, outboxEventService);
        OmsOrder order = ownedOrder(102L, 1L, OrderStatus.WAIT_PAY.code());
        when(orderMapper.selectById(102L)).thenReturn(order);
        when(orderMapper.requestCancellation(eq(102L), eq(1L), any())).thenReturn(1);
        when(paymentCloseService.closeExpired(eq(102L), any())).thenReturn(false);

        channelAwareService.cancel(1L, 102L);

        verify(outboxEventService).enqueue(eq("ORDER"), eq("102"), eq("PAYMENT_CLOSE_REQUESTED"),
                eq("USER_CANCEL_CLOSE:102"), eq(java.util.Map.of("orderId", 102L)));
        verify(inventoryService, never()).releaseForOrderItems(any());
        verify(couponService, never()).releaseCouponUsage(any(), any());
    }

    private OmsOrder ownedOrder(Long id, Long memberId, int status) {
        OmsOrder order = new OmsOrder();
        order.setId(id);
        order.setMemberId(memberId);
        order.setDeleteStatus(0);
        order.setStatus(status);
        return order;
    }
}
