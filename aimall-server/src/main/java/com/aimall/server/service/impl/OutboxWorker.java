package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsOrder;
import com.aimall.server.entity.OmsPaymentRecord;
import com.aimall.server.entity.OutboxEvent;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.mapper.OmsPaymentRecordMapper;
import com.aimall.server.mapper.OutboxEventMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;

@Service
public class OutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private final OutboxEventMapper mapper;
    private final OutboxClaimService claimService;
    private final ObjectMapper objectMapper;
    private final PaymentQueryService paymentQueryService;
    private final PaymentCloseService paymentCloseService;
    private final OmsOrderMapper orderMapper;
    private final OmsPaymentRecordMapper paymentMapper;
    private final LatePaymentProcessor latePaymentProcessor;
    private final RefundTaskProcessor refundTaskProcessor;
    private final String owner = "outbox-" + UUID.randomUUID();
    private final int maxRetry;
    private final int leaseSeconds;
    private final ScheduledExecutorService leaseHeartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "outbox-lease-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public OutboxWorker(OutboxEventMapper mapper, OutboxClaimService claimService, ObjectMapper objectMapper,
                        PaymentQueryService paymentQueryService, PaymentCloseService paymentCloseService,
                        OmsOrderMapper orderMapper, OmsPaymentRecordMapper paymentMapper,
                        LatePaymentProcessor latePaymentProcessor, RefundTaskProcessor refundTaskProcessor,
                        @Value("${aimall.outbox.max-retry:8}") int maxRetry,
                        @Value("${aimall.outbox.lease-seconds:60}") int leaseSeconds) {
        this.mapper = mapper;
        this.claimService = claimService;
        this.objectMapper = objectMapper;
        this.paymentQueryService = paymentQueryService;
        this.paymentCloseService = paymentCloseService;
        this.orderMapper = orderMapper;
        this.paymentMapper = paymentMapper;
        this.latePaymentProcessor = latePaymentProcessor;
        this.refundTaskProcessor = refundTaskProcessor;
        this.maxRetry = Math.max(1, maxRetry);
        this.leaseSeconds = Math.max(10, leaseSeconds);
    }

    @Scheduled(fixedDelayString = "${aimall.outbox.scan-ms:2000}")
    public void consume() {
        for (OutboxEvent candidate : claimService.claim(owner, 50, leaseSeconds)) {
            process(candidate);
        }
    }

    private void process(OutboxEvent event) {
        long heartbeatSeconds = Math.max(5L, Math.min(45L, leaseSeconds * 3L / 4L));
        ScheduledFuture<?> heartbeat = leaseHeartbeat.scheduleAtFixedRate(() -> {
            try {
                if (mapper.renewLease(event.getId(), owner, LocalDateTime.now().plusSeconds(leaseSeconds)) != 1) {
                    log.warn("Outbox lease renewal CAS lost, eventId={}", event.getEventId());
                }
            } catch (Exception exception) {
                log.error("Outbox lease renewal failed, eventId={}", event.getEventId(), exception);
            }
        }, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
        String previousTraceId = MDC.get("traceId");
        if (event.getTraceId() != null && !event.getTraceId().isBlank()) {
            MDC.put("traceId", event.getTraceId());
        }
        try {
            JsonNode envelope = objectMapper.readTree(event.getPayloadJson());
            JsonNode payload = envelope.has("payload") ? envelope.get("payload") : envelope;
            switch (event.getEventType()) {
                case "PAYMENT_QUERY_REQUESTED" -> paymentQueryService.processById(requiredLong(payload, "paymentId"));
                case "PAYMENT_CLOSE_REQUESTED" -> processClose(requiredLong(payload, "orderId"));
                case "LATE_PAYMENT_REFUND_REQUESTED" -> latePaymentProcessor.process(
                        requiredLong(payload, "latePaymentCaseId"));
                case "RefundRequested" -> refundTaskProcessor.process(requiredLong(payload, "refundRecordId"));
                case "OrderCreated", "InventoryReserved", "InventoryReleased", "PaymentCreated",
                     "PaymentSucceeded", "PaymentFailed", "PaymentUnknown", "RefundSucceeded",
                     "KnowledgePublished", "KnowledgeDeleted" ->
                        log.info("Domain event delivered, eventType={}, aggregateId={}, eventId={}",
                                event.getEventType(), event.getAggregateId(), event.getEventId());
                default -> throw new IllegalArgumentException("Unsupported outbox event type: " + event.getEventType());
            }
            if (mapper.markSucceeded(event.getId(), owner) != 1) {
                log.warn("Outbox success CAS lost, eventId={}", event.getEventId());
            }
        } catch (Exception e) {
            int attempt = (event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1;
            String message = limit(e.getMessage(), 1000);
            if (attempt >= maxRetry) {
                mapper.markDeadLetter(event.getId(), owner, e.getClass().getSimpleName(), message);
                log.error("Outbox event moved to dead letter, eventId={}", event.getEventId(), e);
            } else {
                long delaySeconds = Math.min(300, 1L << Math.min(attempt, 8));
                mapper.markRetry(event.getId(), owner, LocalDateTime.now().plusSeconds(delaySeconds),
                        e.getClass().getSimpleName(), message);
                log.warn("Outbox event scheduled for retry, eventId={}, attempt={}", event.getEventId(), attempt, e);
            }
        } finally {
            heartbeat.cancel(false);
            if (previousTraceId == null) {
                MDC.remove("traceId");
            } else {
                MDC.put("traceId", previousTraceId);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        leaseHeartbeat.shutdownNow();
    }

    private void processClose(Long orderId) {
        paymentCloseService.closeExpired(orderId, LocalDateTime.now());
        OmsOrder order = orderMapper.selectById(orderId);
        if (order == null || (order.getStatus() != null && order.getStatus() != 0)) return;
        OmsPaymentRecord payment = paymentMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OmsPaymentRecord>()
                .eq(OmsPaymentRecord::getOrderId, orderId).last("limit 1"));
        if (payment == null) throw new IllegalStateException("Order remained open after local close attempt");
        throw new IllegalStateException("Payment close not converged: " + payment.getPaymentState());
    }

    private Long requiredLong(JsonNode payload, String name) {
        if (!payload.hasNonNull(name) || !payload.get(name).canConvertToLong()) {
            throw new IllegalArgumentException("Missing outbox payload field: " + name);
        }
        return payload.get(name).longValue();
    }

    private String limit(String value, int max) {
        if (value == null) return "unknown";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
