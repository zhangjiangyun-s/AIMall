package com.aimall.server.outbox;

import java.util.Arrays;

public enum OutboxEventType {
    ORDER_CREATED("OrderCreated", true),
    INVENTORY_RESERVED("InventoryReserved", true),
    INVENTORY_RELEASED("InventoryReleased", true),
    PAYMENT_CREATED("PaymentCreated", true),
    PAYMENT_SUCCEEDED("PaymentSucceeded", true),
    PAYMENT_FAILED("PaymentFailed", true),
    PAYMENT_UNKNOWN("PaymentUnknown", true),
    REFUND_REQUESTED("RefundRequested", true),
    REFUND_SUCCEEDED("RefundSucceeded", true),
    KNOWLEDGE_PUBLISHED("KnowledgePublished", true),
    KNOWLEDGE_DELETED("KnowledgeDeleted", true),

    PAYMENT_QUERY_REQUESTED("PAYMENT_QUERY_REQUESTED", false),
    PAYMENT_CLOSE_REQUESTED("PAYMENT_CLOSE_REQUESTED", false),
    LATE_PAYMENT_REFUND_REQUESTED("LATE_PAYMENT_REFUND_REQUESTED", false);

    private final String value;
    private final boolean domainEvent;

    OutboxEventType(String value, boolean domainEvent) {
        this.value = value;
        this.domainEvent = domainEvent;
    }

    public String value() {
        return value;
    }

    public boolean domainEvent() {
        return domainEvent;
    }

    public static OutboxEventType fromValue(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported outbox event type: " + value));
    }
}
