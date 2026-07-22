package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsRefundRecord;
import com.aimall.server.service.RefundGateway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

class RefundTaskProcessorTest {

    @Test
    void retryAfterLocalFailureDoesNotCallRefundChannelTwice() {
        RefundTaskStateService stateService = mock(RefundTaskStateService.class);
        RefundGateway gateway = mock(RefundGateway.class);
        RefundTaskProcessor processor = new RefundTaskProcessor(stateService, gateway);
        OmsRefundRecord pending = refundRecord("PENDING");
        OmsRefundRecord channelSucceeded = refundRecord("CHANNEL_SUCCEEDED");
        when(stateService.get(1000L)).thenReturn(pending, channelSucceeded);
        when(stateService.claimChannelCall(1000L)).thenReturn(true);
        when(gateway.refund("refund-request-1", "ORDER-100", new BigDecimal("100.00")))
                .thenReturn(new RefundGateway.RefundResult("REFUND-TX-1"));
        doThrow(new RuntimeException("local transaction failed"))
                .doNothing()
                .when(stateService).finalizeBusiness(1000L);

        assertThrows(RuntimeException.class, () -> processor.process(1000L));
        processor.process(1000L);

        verify(gateway, times(1)).refund("refund-request-1", "ORDER-100", new BigDecimal("100.00"));
        verify(stateService).recordChannelSuccess(1000L, "REFUND-TX-1");
        verify(stateService, times(2)).finalizeBusiness(1000L);
    }

    @Test
    void unknownRefundIsQueriedAndNeverBlindlySubmittedAgain() {
        RefundTaskStateService stateService = mock(RefundTaskStateService.class);
        RefundGateway gateway = mock(RefundGateway.class);
        RefundTaskProcessor processor = new RefundTaskProcessor(stateService, gateway);
        OmsRefundRecord unknown = refundRecord("REFUND_UNKNOWN");
        when(stateService.get(1000L)).thenReturn(unknown);
        when(stateService.claimRefundQuery(1000L)).thenReturn(true);
        when(gateway.queryRefund("refund-request-1", "ORDER-100", new BigDecimal("100.00")))
                .thenReturn(new RefundGateway.QueryRefundResult(
                        "NOT_FOUND", null, null, "provider-not-found"));

        processor.process(1000L);

        verify(gateway).queryRefund("refund-request-1", "ORDER-100", new BigDecimal("100.00"));
        verify(gateway, never()).refund("refund-request-1", "ORDER-100", new BigDecimal("100.00"));
        verify(stateService).recordRefundQueryNotFound(1000L);
    }

    private OmsRefundRecord refundRecord(String status) {
        OmsRefundRecord record = new OmsRefundRecord();
        record.setId(1000L);
        record.setRequestId("refund-request-1");
        record.setOrderSn("ORDER-100");
        record.setAmount(new BigDecimal("100.00"));
        record.setRefundStatus(status);
        return record;
    }
}
