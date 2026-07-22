package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsPaymentRecord;
import com.aimall.server.entity.PaymentReconciliationBatch;
import com.aimall.server.entity.PaymentReconciliationItem;
import com.aimall.server.entity.PaymentCallbackEvent;
import com.aimall.server.mapper.OmsPaymentRecordMapper;
import com.aimall.server.mapper.OmsRefundRecordMapper;
import com.aimall.server.mapper.PaymentReconciliationBatchMapper;
import com.aimall.server.mapper.PaymentReconciliationItemMapper;
import com.aimall.server.mapper.PaymentCallbackEventMapper;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.payment.AlipayPaymentGateway;
import com.aimall.server.service.RefundGateway;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class PaymentReconciliationServiceTest {

    @Test
    void fullyRefundedPaymentMatchesStoredVerifiedEvidence() {
        PaymentReconciliationBatchMapper batchMapper = mock(PaymentReconciliationBatchMapper.class);
        PaymentReconciliationItemMapper itemMapper = mock(PaymentReconciliationItemMapper.class);
        OmsPaymentRecordMapper paymentMapper = mock(OmsPaymentRecordMapper.class);
        OmsRefundRecordMapper refundMapper = mock(OmsRefundRecordMapper.class);
        PaymentCallbackEventMapper evidenceMapper = mock(PaymentCallbackEventMapper.class);
        PaymentReconciliationService service = new PaymentReconciliationService(
                batchMapper, itemMapper, paymentMapper, refundMapper, evidenceMapper);

        when(batchMapper.reserve(any())).thenAnswer(invocation -> {
            PaymentReconciliationBatch batch = invocation.getArgument(0);
            batch.setId(99L);
            return 1;
        });
        OmsPaymentRecord payment = new OmsPaymentRecord();
        payment.setOrderId(15L);
        payment.setOrderSn("AIM-15");
        payment.setPayStatus("REFUNDED");
        payment.setPaymentState("PAID");
        payment.setAmount(new BigDecimal("1999.00"));
        payment.setPaidAmount(new BigDecimal("1999.00"));
        when(paymentMapper.selectList(any())).thenReturn(List.of(payment));
        when(refundMapper.selectList(any())).thenReturn(List.of());
        PaymentCallbackEvent evidence = new PaymentCallbackEvent();
        evidence.setProviderStatus("TRADE_SUCCESS");
        evidence.setAmount(new BigDecimal("1999.00"));
        evidence.setSignatureValid(1);
        when(evidenceMapper.selectList(any())).thenReturn(List.of(evidence));

        var result = service.run(LocalDate.of(2026, 7, 18));

        assertEquals(1, result.get("checkedCount"));
        assertEquals(0, result.get("differenceCount"));
        verify(itemMapper, never()).insert(any(PaymentReconciliationItem.class));
        verify(batchMapper).finish(99L, "COMPLETED", 1, 0);
    }

    @Test
    void lateChannelPaymentAfterCloseCreatesStandardDifferenceAndFinancialHold() {
        PaymentReconciliationBatchMapper batchMapper = mock(PaymentReconciliationBatchMapper.class);
        PaymentReconciliationItemMapper itemMapper = mock(PaymentReconciliationItemMapper.class);
        OmsPaymentRecordMapper paymentMapper = mock(OmsPaymentRecordMapper.class);
        OmsRefundRecordMapper refundMapper = mock(OmsRefundRecordMapper.class);
        PaymentCallbackEventMapper evidenceMapper = mock(PaymentCallbackEventMapper.class);
        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        AlipayPaymentGateway paymentGateway = mock(AlipayPaymentGateway.class);
        RefundGateway refundGateway = mock(RefundGateway.class);
        PaymentReconciliationService service = new PaymentReconciliationService(
                batchMapper, itemMapper, paymentMapper, refundMapper, evidenceMapper,
                orderMapper, paymentGateway, refundGateway);
        when(batchMapper.reserve(any())).thenAnswer(invocation -> {
            PaymentReconciliationBatch batch = invocation.getArgument(0);
            batch.setId(101L);
            return 1;
        });
        OmsPaymentRecord payment = payment(21L, "CLOSED", "CLOSED", "100.00");
        when(paymentMapper.selectList(any())).thenReturn(List.of(payment));
        when(refundMapper.selectList(any())).thenReturn(List.of());
        when(evidenceMapper.selectList(any())).thenReturn(List.of());
        when(paymentGateway.query("AIM-21")).thenReturn(new AlipayPaymentGateway.QueryResult(
                "TRADE_SUCCESS", "ALI-21", new BigDecimal("100.00"), "buyer", "query-evidence"));

        var result = service.run(LocalDate.of(2026, 7, 21));

        assertEquals(1, result.get("differenceCount"));
        ArgumentCaptor<PaymentReconciliationItem> item = ArgumentCaptor.forClass(PaymentReconciliationItem.class);
        verify(itemMapper).insert(item.capture());
        assertEquals("LATE_PAYMENT_AFTER_CLOSE", item.getValue().getDifferenceType());
        assertEquals("COMPLETED", item.getValue().getAutoQueryStatus());
        verify(orderMapper).placeFinancialHold(21L, "PAYMENT_RECONCILIATION:LATE_PAYMENT_AFTER_CLOSE:null");
    }

    @Test
    void duplicateCallbacksAndAmountMismatchRemainSeparateStandardDifferences() {
        PaymentReconciliationBatchMapper batchMapper = mock(PaymentReconciliationBatchMapper.class);
        PaymentReconciliationItemMapper itemMapper = mock(PaymentReconciliationItemMapper.class);
        OmsPaymentRecordMapper paymentMapper = mock(OmsPaymentRecordMapper.class);
        OmsRefundRecordMapper refundMapper = mock(OmsRefundRecordMapper.class);
        PaymentCallbackEventMapper evidenceMapper = mock(PaymentCallbackEventMapper.class);
        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        AlipayPaymentGateway paymentGateway = mock(AlipayPaymentGateway.class);
        PaymentReconciliationService service = new PaymentReconciliationService(
                batchMapper, itemMapper, paymentMapper, refundMapper, evidenceMapper,
                orderMapper, paymentGateway, mock(RefundGateway.class));
        when(batchMapper.reserve(any())).thenAnswer(invocation -> {
            PaymentReconciliationBatch batch = invocation.getArgument(0);
            batch.setId(102L);
            return 1;
        });
        OmsPaymentRecord payment = payment(22L, "PAID", "PAID", "100.00");
        when(paymentMapper.selectList(any())).thenReturn(List.of(payment));
        when(refundMapper.selectList(any())).thenReturn(List.of());
        PaymentCallbackEvent first = callback("90.00");
        PaymentCallbackEvent second = callback("90.00");
        when(evidenceMapper.selectList(any())).thenReturn(List.of(first, second));
        when(paymentGateway.query("AIM-22")).thenReturn(new AlipayPaymentGateway.QueryResult(
                "TRADE_SUCCESS", "ALI-22", new BigDecimal("90.00"), "buyer", "query-evidence"));

        var result = service.run(LocalDate.of(2026, 7, 21));

        assertEquals(2, result.get("differenceCount"));
        ArgumentCaptor<PaymentReconciliationItem> items = ArgumentCaptor.forClass(PaymentReconciliationItem.class);
        verify(itemMapper, times(2)).insert(items.capture());
        assertEquals(List.of("DUPLICATE_CALLBACK", "AMOUNT_MISMATCH"),
                items.getAllValues().stream().map(PaymentReconciliationItem::getDifferenceType).toList());
        verify(orderMapper, times(2)).placeFinancialHold(org.mockito.ArgumentMatchers.eq(22L), any());
    }

    private OmsPaymentRecord payment(Long orderId, String payStatus, String state, String amount) {
        OmsPaymentRecord payment = new OmsPaymentRecord();
        payment.setId(orderId + 1000);
        payment.setOrderId(orderId);
        payment.setOrderSn("AIM-" + orderId);
        payment.setPayStatus(payStatus);
        payment.setPaymentState(state);
        payment.setAmount(new BigDecimal(amount));
        payment.setPaidAmount(new BigDecimal(amount));
        return payment;
    }

    private PaymentCallbackEvent callback(String amount) {
        PaymentCallbackEvent event = new PaymentCallbackEvent();
        event.setProviderStatus("TRADE_SUCCESS");
        event.setAmount(new BigDecimal(amount));
        event.setSignatureValid(1);
        return event;
    }
}
