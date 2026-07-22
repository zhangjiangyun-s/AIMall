package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsOrder;
import com.aimall.server.entity.OmsOrderItem;
import com.aimall.server.entity.OmsShipment;
import com.aimall.server.entity.OmsShipmentItem;
import com.aimall.server.entity.OmsLogisticsEvent;
import com.aimall.server.mapper.OmsLogisticsEventMapper;
import com.aimall.server.mapper.OmsOrderItemMapper;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.mapper.OmsPaymentRecordMapper;
import com.aimall.server.mapper.OmsShipmentItemMapper;
import com.aimall.server.mapper.OmsShipmentMapper;
import com.aimall.server.service.LogisticsService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.math.BigDecimal;
import com.aimall.server.entity.OmsPaymentRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogisticsServiceImplTest {

    @Test
    void splitShipmentAtomicallyOccupiesRequestedQuantity() {
        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        OmsOrderItemMapper itemMapper = mock(OmsOrderItemMapper.class);
        OmsShipmentMapper shipmentMapper = mock(OmsShipmentMapper.class);
        OmsShipmentItemMapper shipmentItemMapper = mock(OmsShipmentItemMapper.class);
        OmsLogisticsEventMapper eventMapper = mock(OmsLogisticsEventMapper.class);
        ShipmentEligibilityService eligibility = new ShipmentEligibilityService(
                paidPaymentMapper(100L, new BigDecimal("100.00")), itemMapper);
        LogisticsServiceImpl service = new LogisticsServiceImpl(
                orderMapper, itemMapper, shipmentMapper, shipmentItemMapper, eventMapper, eligibility
        );
        OmsOrder order = new OmsOrder();
        order.setId(100L);
        order.setOrderSn("ORDER-100");
        order.setStatus(1);
        order.setDeleteStatus(0);
        order.setPayAmount(new BigDecimal("100.00"));
        order.setRefundStatus("NONE");
        when(orderMapper.selectById(100L)).thenReturn(order);
        OmsOrderItem selected = new OmsOrderItem();
        selected.setId(200L);
        selected.setOrderId(100L);
        selected.setProductQuantity(2);
        selected.setShippedQuantity(0);
        selected.setReturnReservedQuantity(0);
        selected.setRefundedQuantity(0);
        when(itemMapper.selectList(any())).thenReturn(List.of(selected));
        when(itemMapper.addShippedQuantity(200L, 100L, 1)).thenReturn(1);
        when(shipmentMapper.insert(any(OmsShipment.class))).thenAnswer(invocation -> {
            OmsShipment shipment = invocation.getArgument(0);
            shipment.setId(300L);
            return 1;
        });
        when(orderMapper.ship(any(), any(), any(), any())).thenReturn(1);

        var result = service.ship(
                100L,
                "SF",
                "顺丰速运",
                "SF100001",
                List.of(new LogisticsService.ShipmentItemRequest(200L, 1))
        );

        assertEquals(300L, result.get("shipmentId"));
        verify(itemMapper).addShippedQuantity(200L, 100L, 1);
        verify(shipmentItemMapper).insert(any(OmsShipmentItem.class));
        verify(eventMapper).insert(any(OmsLogisticsEvent.class));
    }

    @Test
    void financialHoldBlocksShipmentBeforeAnyWrite() {
        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        OmsOrderItemMapper itemMapper = mock(OmsOrderItemMapper.class);
        OmsShipmentMapper shipmentMapper = mock(OmsShipmentMapper.class);
        OmsPaymentRecordMapper paymentMapper = mock(OmsPaymentRecordMapper.class);
        LogisticsServiceImpl service = new LogisticsServiceImpl(orderMapper, itemMapper, shipmentMapper,
                mock(OmsShipmentItemMapper.class), mock(OmsLogisticsEventMapper.class),
                new ShipmentEligibilityService(paymentMapper, itemMapper));
        OmsOrder order = new OmsOrder();
        order.setId(101L);
        order.setDeleteStatus(0);
        order.setStatus(1);
        order.setFinancialHold(1);
        when(orderMapper.selectById(101L)).thenReturn(order);

        RuntimeException error = assertThrows(RuntimeException.class, () -> service.ship(
                101L, "SF", "顺丰速运", "SF-HOLD",
                List.of(new LogisticsService.ShipmentItemRequest(201L, 1))));

        assertEquals("订单存在资金对账或风险冻结，已阻止发货", error.getMessage());
        verify(shipmentMapper, org.mockito.Mockito.never()).insert(any(OmsShipment.class));
    }

    private OmsPaymentRecordMapper paidPaymentMapper(Long orderId, BigDecimal amount) {
        OmsPaymentRecordMapper mapper = mock(OmsPaymentRecordMapper.class);
        OmsPaymentRecord payment = new OmsPaymentRecord();
        payment.setOrderId(orderId);
        payment.setPayStatus("PAID");
        payment.setPaymentState("PAID");
        payment.setPaidAmount(amount);
        payment.setRefundedAmount(BigDecimal.ZERO);
        when(mapper.selectOne(any())).thenReturn(payment);
        return mapper;
    }
}
