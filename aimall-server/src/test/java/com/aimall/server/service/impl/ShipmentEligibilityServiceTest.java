package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsOrder;
import com.aimall.server.entity.OmsOrderItem;
import com.aimall.server.entity.OmsPaymentRecord;
import com.aimall.server.mapper.OmsOrderItemMapper;
import com.aimall.server.mapper.OmsPaymentRecordMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShipmentEligibilityServiceTest {
    @Test
    void allowsOnlyFullyPaidUnrefundedSellableQuantity() {
        Fixture fixture = fixture("100.0000", "100.0000", "0.0000", "PAID", "PAID");
        assertDoesNotThrow(() -> fixture.service.assertEligible(fixture.order, Map.of(20L, 2)));
    }

    @Test
    void partialPaymentIsRejected() {
        Fixture fixture = fixture("100.0000", "99.9900", "0.0000", "PAID", "PAID");
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> fixture.service.assertEligible(fixture.order, Map.of(20L, 1)));
        assertEquals("订单未完成全额支付，禁止发货", error.getMessage());
    }

    @Test
    void refundedOrRiskPaymentIsRejected() {
        Fixture refunded = fixture("100.0000", "100.0000", "1.0000", "PAID", "PAID");
        assertThrows(RuntimeException.class, () -> refunded.service.assertEligible(refunded.order, Map.of(20L, 1)));

        Fixture risk = fixture("100.0000", "100.0000", "0.0000", "PAID", "RISK_HOLD");
        assertThrows(RuntimeException.class, () -> risk.service.assertEligible(risk.order, Map.of(20L, 1)));
    }

    @Test
    void returnReservedAndRefundedQuantityReduceSellableRemainder() {
        Fixture fixture = fixture("100.0000", "100.0000", "0.0000", "PAID", "PAID");
        fixture.item.setProductQuantity(5);
        fixture.item.setShippedQuantity(1);
        fixture.item.setReturnReservedQuantity(2);
        fixture.item.setRefundedQuantity(1);
        assertDoesNotThrow(() -> fixture.service.assertEligible(fixture.order, Map.of(20L, 1)));
        assertThrows(RuntimeException.class, () -> fixture.service.assertEligible(fixture.order, Map.of(20L, 2)));
    }

    private Fixture fixture(String payable, String paid, String refunded, String payStatus, String paymentState) {
        OmsPaymentRecordMapper paymentMapper = mock(OmsPaymentRecordMapper.class);
        OmsOrderItemMapper itemMapper = mock(OmsOrderItemMapper.class);
        OmsOrder order = new OmsOrder();
        order.setId(10L);
        order.setStatus(1);
        order.setDeleteStatus(0);
        order.setFinancialHold(0);
        order.setRefundStatus("NONE");
        order.setPayAmount(new BigDecimal(payable));
        OmsPaymentRecord payment = new OmsPaymentRecord();
        payment.setOrderId(10L);
        payment.setPayStatus(payStatus);
        payment.setPaymentState(paymentState);
        payment.setPaidAmount(new BigDecimal(paid));
        payment.setRefundedAmount(new BigDecimal(refunded));
        OmsOrderItem item = new OmsOrderItem();
        item.setId(20L);
        item.setOrderId(10L);
        item.setProductQuantity(5);
        item.setShippedQuantity(0);
        item.setReturnReservedQuantity(0);
        item.setRefundedQuantity(0);
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        return new Fixture(new ShipmentEligibilityService(paymentMapper, itemMapper), order, item);
    }

    private record Fixture(ShipmentEligibilityService service, OmsOrder order, OmsOrderItem item) {}
}
