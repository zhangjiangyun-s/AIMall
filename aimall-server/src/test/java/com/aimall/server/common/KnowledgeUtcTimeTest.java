package com.aimall.server.common;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeUtcTimeTest {
    @Test
    void convertsKnowledgeLocalTimeToUtcIsoInstant() {
        assertEquals("2026-07-18T04:30:00Z", KnowledgeUtcTime.format(
                LocalDateTime.of(2026, 7, 18, 12, 30, 0), ZoneId.of("Asia/Shanghai")
        ));
        assertEquals("", KnowledgeUtcTime.format(null));
    }
}
