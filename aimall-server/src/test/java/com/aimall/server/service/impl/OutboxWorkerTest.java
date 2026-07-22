package com.aimall.server.service.impl;

import com.aimall.server.entity.OutboxEvent;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.mapper.OmsPaymentRecordMapper;
import com.aimall.server.mapper.OutboxEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxWorkerTest {
    @Test
    void refundRequestedIsConsumedByDurableWorkerAndMarkedSent() {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        OutboxClaimService claimService = mock(OutboxClaimService.class);
        RefundTaskProcessor refundProcessor = mock(RefundTaskProcessor.class);
        OutboxEvent event = refundRequested();
        when(claimService.claim(anyString(), eq(50), eq(60))).thenReturn(List.of(event));
        when(mapper.markSucceeded(eq(7L), anyString())).thenReturn(1);
        OutboxWorker worker = worker(mapper, claimService, refundProcessor, 8);

        worker.consume();

        verify(refundProcessor).process(99L);
        verify(mapper).markSucceeded(eq(7L), anyString());
        verify(mapper, never()).markRetry(any(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void failedConsumerUsesBackoffWithoutMarkingSent() {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        OutboxClaimService claimService = mock(OutboxClaimService.class);
        RefundTaskProcessor refundProcessor = mock(RefundTaskProcessor.class);
        OutboxEvent event = refundRequested();
        when(claimService.claim(anyString(), eq(50), eq(60))).thenReturn(List.of(event));
        org.mockito.Mockito.doThrow(new RuntimeException("provider unavailable"))
                .when(refundProcessor).process(99L);
        OutboxWorker worker = worker(mapper, claimService, refundProcessor, 8);

        worker.consume();

        verify(mapper).markRetry(eq(7L), anyString(), any(LocalDateTime.class),
                eq("RuntimeException"), eq("provider unavailable"));
        verify(mapper, never()).markSucceeded(any(), anyString());
    }

    private OutboxWorker worker(OutboxEventMapper mapper, OutboxClaimService claimService,
                                RefundTaskProcessor refundProcessor, int maxRetry) {
        return new OutboxWorker(
                mapper, claimService, new ObjectMapper(), mock(PaymentQueryService.class), mock(PaymentCloseService.class),
                mock(OmsOrderMapper.class), mock(OmsPaymentRecordMapper.class),
                mock(LatePaymentProcessor.class), refundProcessor, maxRetry, 60
        );
    }

    private OutboxEvent refundRequested() {
        OutboxEvent event = new OutboxEvent();
        event.setId(7L);
        event.setEventId("event-7");
        event.setAggregateId("99");
        event.setEventType("RefundRequested");
        event.setRetryCount(0);
        event.setPayloadJson("{\"eventId\":\"event-7\",\"payload\":{\"refundRecordId\":99}}");
        return event;
    }
}
