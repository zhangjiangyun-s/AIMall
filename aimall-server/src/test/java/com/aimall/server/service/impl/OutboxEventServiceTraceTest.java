package com.aimall.server.service.impl;

import com.aimall.server.entity.OutboxEvent;
import com.aimall.server.mapper.OutboxEventMapper;
import com.aimall.server.outbox.OutboxEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxEventServiceTraceTest {
    @Test
    void enqueueKeepsCurrentRequestTraceId() {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        when(mapper.insertIgnore(any())).thenReturn(1);
        OutboxEventService service = new OutboxEventService(mapper, new ObjectMapper());

        MDC.put("traceId", "stage9-outbox-trace");
        try {
            service.enqueue("ORDER", "1", "PAYMENT_QUERY_REQUESTED", "key-1", Map.of("paymentId", 1));
        } finally {
            MDC.remove("traceId");
        }

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(mapper).insertIgnore(captor.capture());
        assertEquals("stage9-outbox-trace", captor.getValue().getTraceId());
    }

    @Test
    void enqueuePersistsVersionedCanonicalEnvelope() throws Exception {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        when(mapper.insertIgnore(any())).thenReturn(1);
        ObjectMapper objectMapper = new ObjectMapper();
        OutboxEventService service = new OutboxEventService(mapper, objectMapper);

        service.enqueue("ORDER", "42", 3L, OutboxEventType.ORDER_CREATED,
                "ORDER_CREATED:42", 2, Map.of("orderId", 42L));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(mapper).insertIgnore(captor.capture());
        OutboxEvent event = captor.getValue();
        JsonNode envelope = objectMapper.readTree(event.getPayloadJson());
        assertEquals("OrderCreated", event.getEventType());
        assertEquals(3L, event.getAggregateVersion());
        assertEquals(2, event.getPayloadSchemaVersion());
        assertNotNull(event.getOccurredAtUtc());
        assertEquals("aimall-server/0.0.1", event.getProducerVersion());
        assertEquals("default", event.getTenantId());
        assertEquals(event.getEventId(), envelope.get("eventId").asText());
        assertEquals("42", envelope.get("aggregateId").asText());
        assertEquals(3L, envelope.get("aggregateVersion").asLong());
        assertEquals("OrderCreated", envelope.get("eventType").asText());
        assertFalse(envelope.get("occurredAtUtc").asText().isBlank());
        assertEquals("ORDER_CREATED:42", envelope.get("idempotencyKey").asText());
        assertEquals("default", envelope.get("tenantId").asText());
        assertEquals(2, envelope.get("payloadSchemaVersion").asInt());
        assertEquals(42L, envelope.get("payload").get("orderId").asLong());
    }

    @Test
    void duplicateIdempotencyKeyReturnsExistingFactWithoutInventingSuccess() {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        when(mapper.insertIgnore(any())).thenReturn(0);
        OutboxEventService service = new OutboxEventService(mapper, new ObjectMapper());

        assertFalse(service.enqueue("ORDER", "42", 1L, OutboxEventType.ORDER_CREATED,
                "ORDER_CREATED:42", 1, Map.of("orderId", 42L)));
    }
}
