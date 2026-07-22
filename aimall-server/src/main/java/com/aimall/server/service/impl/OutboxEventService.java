package com.aimall.server.service.impl;

import com.aimall.server.entity.OutboxEvent;
import com.aimall.server.mapper.OutboxEventMapper;
import com.aimall.server.outbox.OutboxEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.MDC;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class OutboxEventService {
    private final OutboxEventMapper mapper;
    private final ObjectMapper objectMapper;
    @Value("${aimall.outbox.producer-version:aimall-server/0.0.1}")
    private String producerVersion = "aimall-server/0.0.1";
    @Value("${aimall.tenant.default-id:default}")
    private String defaultTenantId = "default";

    public OutboxEventService(OutboxEventMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public boolean enqueue(String aggregateType, String aggregateId, String eventType,
                           String idempotencyKey, Map<String, Object> payload) {
        return enqueue(aggregateType, aggregateId, 0L, OutboxEventType.fromValue(eventType),
                idempotencyKey, 1, payload);
    }

    public boolean enqueue(String aggregateType, String aggregateId, long aggregateVersion,
                           OutboxEventType eventType, String idempotencyKey,
                           int payloadSchemaVersion, Map<String, Object> payload) {
        try {
            OutboxEvent event = new OutboxEvent();
            event.setEventId(UUID.randomUUID().toString());
            event.setAggregateType(aggregateType);
            event.setAggregateId(aggregateId);
            event.setAggregateVersion(Math.max(0L, aggregateVersion));
            event.setEventType(eventType.value());
            event.setIdempotencyKey(idempotencyKey);
            String traceId = MDC.get("traceId");
            event.setTraceId(traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId);
            event.setTenantId(defaultTenantId);
            event.setOccurredAtUtc(LocalDateTime.now(ZoneOffset.UTC));
            event.setProducerVersion(producerVersion);
            event.setPayloadSchemaVersion(Math.max(1, payloadSchemaVersion));
            event.setPayloadJson(objectMapper.writeValueAsString(envelope(event, payload)));
            event.setPayloadHash(sha256(event.getPayloadJson()));
            return mapper.insertIgnore(event) == 1;
        } catch (Exception e) {
            throw new IllegalStateException("Outbox 事件创建失败", e);
        }
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, Object> envelope(OutboxEvent event, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", event.getEventId());
        envelope.put("aggregateId", event.getAggregateId());
        envelope.put("aggregateVersion", event.getAggregateVersion());
        envelope.put("eventType", event.getEventType());
        envelope.put("occurredAtUtc", event.getOccurredAtUtc().atOffset(ZoneOffset.UTC).toString());
        envelope.put("traceId", event.getTraceId());
        envelope.put("tenantId", event.getTenantId());
        envelope.put("idempotencyKey", event.getIdempotencyKey());
        envelope.put("producerVersion", event.getProducerVersion());
        envelope.put("payloadSchemaVersion", event.getPayloadSchemaVersion());
        envelope.put("payload", payload == null ? Map.of() : payload);
        return envelope;
    }
}
