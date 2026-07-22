package com.aimall.server.service.impl;

import com.aimall.server.entity.LatePaymentCase;
import com.aimall.server.mapper.LatePaymentCaseMapper;
import com.aimall.server.service.RefundGateway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LatePaymentProcessorTest {

    @Test
    void newLatePaymentUsesAnIdempotentRefundRequest() {
        LatePaymentCaseMapper mapper = mock(LatePaymentCaseMapper.class);
        RefundGateway gateway = mock(RefundGateway.class);
        LatePaymentStateService stateService = mock(LatePaymentStateService.class);
        LatePaymentProcessor processor = new LatePaymentProcessor(mapper, gateway, stateService);
        LatePaymentCase pending = value("PENDING");
        LatePaymentCase processing = value("REFUNDING");
        when(mapper.selectById(1L)).thenReturn(pending, processing);
        when(mapper.claimRefund(1L)).thenReturn(1);
        when(gateway.refund("LATE-5", "AIM-15", new BigDecimal("1999.00")))
                .thenReturn(new RefundGateway.RefundResult("ALI-REFUND-1"));

        processor.process(1L);

        verify(stateService).finalizeRefund(processing, "ALI-REFUND-1");
    }

    @Test
    void unknownLateRefundIsQueriedInsteadOfSubmittedAgain() {
        LatePaymentCaseMapper mapper = mock(LatePaymentCaseMapper.class);
        RefundGateway gateway = mock(RefundGateway.class);
        LatePaymentStateService stateService = mock(LatePaymentStateService.class);
        LatePaymentProcessor processor = new LatePaymentProcessor(mapper, gateway, stateService);
        LatePaymentCase unknown = value("REFUND_UNKNOWN");
        when(mapper.selectById(1L)).thenReturn(unknown);
        when(mapper.claimQuery(1L)).thenReturn(1);
        when(gateway.queryRefund("LATE-5", "AIM-15", new BigDecimal("1999.00")))
                .thenReturn(new RefundGateway.QueryRefundResult(
                        "SUCCEEDED", "ALI-REFUND-1", new BigDecimal("1999.00"), "{}"));

        processor.process(1L);

        verify(stateService).finalizeRefund(unknown, "ALI-REFUND-1");
        verify(gateway, org.mockito.Mockito.never()).refund(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void uncertainRefundIsPersistedBeforeOutboxRetries() {
        LatePaymentCaseMapper mapper = mock(LatePaymentCaseMapper.class);
        RefundGateway gateway = mock(RefundGateway.class);
        LatePaymentStateService stateService = mock(LatePaymentStateService.class);
        LatePaymentProcessor processor = new LatePaymentProcessor(mapper, gateway, stateService);
        when(mapper.selectById(1L)).thenReturn(value("PENDING"), value("REFUNDING"));
        when(mapper.claimRefund(1L)).thenReturn(1);
        when(gateway.refund("LATE-5", "AIM-15", new BigDecimal("1999.00")))
                .thenThrow(new IllegalStateException("timeout"));

        assertThrows(IllegalStateException.class, () -> processor.process(1L));

        verify(mapper).markUnknown(1L, "timeout");
    }

    private LatePaymentCase value(String status) {
        LatePaymentCase value = new LatePaymentCase();
        value.setId(1L);
        value.setPaymentId(5L);
        value.setOrderId(15L);
        value.setOrderSn("AIM-15");
        value.setAmount(new BigDecimal("1999.00"));
        value.setRefundRequestId("LATE-5");
        value.setStatus(status);
        return value;
    }
}
