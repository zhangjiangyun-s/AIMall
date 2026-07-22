package com.aimall.server.outbox;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutboxEventTypeTest {
    @Test
    void stage15FixedDomainEventsAreExactAndVersionedByRegistry() {
        Set<String> actual = Stream.of(OutboxEventType.values())
                .filter(OutboxEventType::domainEvent)
                .map(OutboxEventType::value)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "OrderCreated", "InventoryReserved", "InventoryReleased", "PaymentCreated",
                "PaymentSucceeded", "PaymentFailed", "PaymentUnknown", "RefundRequested",
                "RefundSucceeded", "KnowledgePublished", "KnowledgeDeleted"
        ), actual);
    }
}
