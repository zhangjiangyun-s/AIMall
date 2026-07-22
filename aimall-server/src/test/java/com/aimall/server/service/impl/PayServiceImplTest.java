package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsOrder;
import com.aimall.server.entity.OmsPaymentRecord;
import com.aimall.server.mapper.OmsOrderItemMapper;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.mapper.OmsPaymentRecordMapper;
import com.aimall.server.order.OrderStatus;
import com.aimall.server.service.InventoryService;
import com.aimall.server.config.AlipayProperties;
import com.aimall.server.payment.AlipaySigner;
import com.aimall.server.mapper.PaymentCallbackEventMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PayServiceImplTest {

    @Test
    void tenDuplicatePaidCallbacksOnlyDeductInventoryAndTransitionPaymentOnce() {
        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        OmsOrderItemMapper itemMapper = mock(OmsOrderItemMapper.class);
        OmsPaymentRecordMapper paymentMapper = mock(OmsPaymentRecordMapper.class);
        InventoryService inventoryService = mock(InventoryService.class);
        PaymentCallbackEventMapper callbackMapper = mock(PaymentCallbackEventMapper.class);
        AlipayProperties properties = new AlipayProperties();
        properties.setEnabled(true);
        properties.setAppId("APP-1");
        properties.setSellerId("SELLER-1");
        AlipaySigner signer = mock(AlipaySigner.class);
        PayServiceImpl payService = new PayServiceImpl(
                orderMapper, itemMapper, paymentMapper, inventoryService, properties, signer,
                callbackMapper, null, null, mock(PaymentEvidenceService.class)
        );
        MoneySnapshotService moneySnapshotService = mock(MoneySnapshotService.class);
        ReflectionTestUtils.setField(payService, "moneySnapshotService", moneySnapshotService);
        OmsOrder order = new OmsOrder();
        order.setId(15L);
        order.setMemberId(7L);
        order.setOrderSn("AIM-15");
        order.setPayAmount(new BigDecimal("1999.00"));
        order.setStatus(OrderStatus.WAIT_PAY.code());
        OmsPaymentRecord payment = new OmsPaymentRecord();
        payment.setId(5L);
        payment.setOrderId(15L);
        payment.setAmount(new BigDecimal("1999.00"));
        payment.setPaymentState("PENDING");
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.selectById(15L)).thenReturn(order);
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(paymentMapper.selectById(5L)).thenReturn(payment);
        when(callbackMapper.insertIgnore(any())).thenReturn(1, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        when(orderMapper.markPaid(any(), any(), any(Integer.class), any())).thenReturn(1);
        when(paymentMapper.markAlipayPaid(any(), any(), any(), any(), any())).thenReturn(1);
        Map<String, String> params = Map.of(
                "trade_no", "ALI-15", "out_trade_no", "AIM-15", "trade_status", "TRADE_SUCCESS",
                "total_amount", "1999.00", "app_id", "APP-1", "seller_id", "SELLER-1",
                "gmt_payment", "2026-07-19 10:00:00", "sign", "VALID"
        );
        when(signer.verify(params, "VALID")).thenReturn(true);

        for (int attempt = 0; attempt < 10; attempt++) {
            assertEquals("success", payService.handleAlipayNotify(params));
        }

        verify(callbackMapper, times(10)).insertIgnore(any());
        verify(inventoryService, times(1)).deductForOrderItems(List.of());
        verify(orderMapper, times(1)).markPaid(eq(15L), eq(7L), eq(1), any());
        verify(paymentMapper, times(1)).markAlipayPaid(eq(15L), eq(new BigDecimal("1999.00")),
                eq("ALI-15"), any(), any());
        verify(moneySnapshotService).record(order);
        verify(moneySnapshotService).record(payment);
    }

    @Test
    void verifiedAlipayReturnIsStoredAsEvidenceWithoutMarkingTheOrderPaid() {
        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        OmsOrderItemMapper orderItemMapper = mock(OmsOrderItemMapper.class);
        OmsPaymentRecordMapper paymentRecordMapper = mock(OmsPaymentRecordMapper.class);
        InventoryService inventoryService = mock(InventoryService.class);
        AlipayProperties properties = new AlipayProperties();
        properties.setEnabled(true);
        properties.setAppId("APP-1");
        properties.setSellerId("SELLER-1");
        properties.setReturnUrl("http://localhost:5173/orders");
        AlipaySigner signer = mock(AlipaySigner.class);
        PaymentEvidenceService evidenceService = mock(PaymentEvidenceService.class);
        PayServiceImpl payService = new PayServiceImpl(orderMapper, orderItemMapper, paymentRecordMapper,
                inventoryService, properties, signer, mock(PaymentCallbackEventMapper.class),
                null, null, evidenceService);
        OmsOrder order = new OmsOrder();
        order.setId(15L);
        order.setOrderSn("AIM-15");
        order.setPayAmount(new BigDecimal("1999.00"));
        when(orderMapper.selectOne(any())).thenReturn(order);
        Map<String, String> params = Map.of(
                "out_trade_no", "AIM-15", "trade_no", "ALI-15", "total_amount", "1999.00",
                "app_id", "APP-1", "seller_id", "SELLER-1", "sign", "VALID");
        when(signer.verify(params, "VALID")).thenReturn(true);

        String redirect = payService.handleAlipayReturn(params);

        assertEquals("http://localhost:5173/orders?alipayReturn=verified&orderSn=AIM-15", redirect);
        verify(evidenceService).recordVerifiedReturn(15L, "AIM-15", "ALI-15",
                new BigDecimal("1999.00"), params.toString());
        verify(orderMapper, never()).markPaid(any(), any(), any(Integer.class), any());
        verify(inventoryService, never()).deductForOrderItems(any());
    }

    @Test
    void unverifiedAlipayReturnCannotCreateEvidence() {
        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        PaymentEvidenceService evidenceService = mock(PaymentEvidenceService.class);
        AlipayProperties properties = new AlipayProperties();
        properties.setEnabled(true);
        properties.setAppId("APP-1");
        AlipaySigner signer = mock(AlipaySigner.class);
        PayServiceImpl payService = new PayServiceImpl(orderMapper, mock(OmsOrderItemMapper.class),
                mock(OmsPaymentRecordMapper.class), mock(InventoryService.class), properties, signer,
                mock(PaymentCallbackEventMapper.class), null, null, evidenceService);
        Map<String, String> params = Map.of("out_trade_no", "AIM-15", "trade_no", "ALI-15",
                "total_amount", "1999.00", "app_id", "APP-1", "sign", "INVALID");
        when(signer.verify(params, "INVALID")).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> payService.handleAlipayReturn(params));

        verifyNoMoreInteractions(evidenceService);
    }

    @Test
    void paidCallbackForClosedOrderCreatesLatePaymentCaseWithoutDeductingInventory() {
        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        OmsOrderItemMapper itemMapper = mock(OmsOrderItemMapper.class);
        OmsPaymentRecordMapper paymentMapper = mock(OmsPaymentRecordMapper.class);
        InventoryService inventoryService = mock(InventoryService.class);
        PaymentCallbackEventMapper callbackMapper = mock(PaymentCallbackEventMapper.class);
        LatePaymentStateService latePaymentStateService = mock(LatePaymentStateService.class);
        AlipayProperties properties = new AlipayProperties();
        properties.setEnabled(true);
        properties.setAppId("APP-1");
        properties.setSellerId("SELLER-1");
        AlipaySigner signer = mock(AlipaySigner.class);
        PayServiceImpl payService = new PayServiceImpl(orderMapper, itemMapper, paymentMapper,
                inventoryService, properties, signer, callbackMapper, null, null,
                mock(PaymentEvidenceService.class), latePaymentStateService);
        OmsOrder order = new OmsOrder();
        order.setId(15L);
        order.setOrderSn("AIM-15");
        order.setPayAmount(new BigDecimal("1999.00"));
        order.setStatus(OrderStatus.CLOSED.code());
        OmsPaymentRecord payment = new OmsPaymentRecord();
        payment.setId(5L);
        payment.setOrderId(15L);
        payment.setAmount(new BigDecimal("1999.00"));
        payment.setPaymentState("CLOSED");
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(callbackMapper.insertIgnore(any())).thenReturn(1);
        Map<String, String> params = Map.of(
                "trade_no", "ALI-15", "out_trade_no", "AIM-15", "trade_status", "TRADE_SUCCESS",
                "total_amount", "1999.00", "app_id", "APP-1", "seller_id", "SELLER-1", "sign", "VALID");
        when(signer.verify(params, "VALID")).thenReturn(true);

        assertEquals("success", payService.handleAlipayNotify(params));

        verify(latePaymentStateService).recordDetected(order, payment, "ALI-15",
                new BigDecimal("1999.00"), params.toString());
        verify(inventoryService, never()).deductForOrderItems(any());
        verify(orderMapper, never()).markPaid(any(), any(), any(Integer.class), any());
    }

    @Test
    void paymentStopsWhenOrderCasTransitionFails() {
        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        OmsOrderItemMapper orderItemMapper = mock(OmsOrderItemMapper.class);
        OmsPaymentRecordMapper paymentRecordMapper = mock(OmsPaymentRecordMapper.class);
        InventoryService inventoryService = mock(InventoryService.class);
        PayServiceImpl payService = new PayServiceImpl(
                orderMapper,
                orderItemMapper,
                paymentRecordMapper,
                inventoryService
        );
        OmsOrder order = new OmsOrder();
        order.setId(100L);
        order.setMemberId(1L);
        order.setDeleteStatus(0);
        order.setStatus(OrderStatus.WAIT_PAY.code());
        order.setInventoryReservationStatus("RESERVED");
        when(orderMapper.selectById(100L)).thenReturn(order);
        when(orderItemMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.markPaid(any(), any(), any(Integer.class), any())).thenReturn(0);

        assertThrows(RuntimeException.class, () -> payService.simulatePay(100L, 1L));

        verify(inventoryService).deductForOrderItems(List.of());
        verify(paymentRecordMapper, never()).insert(any(OmsPaymentRecord.class));
    }
}
