package com.aimall.server.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveLogSanitizerTest {
    @Test
    void masksCredentialsPhonesAndRawPayloads() {
        String sanitized = SensitiveLogSanitizer.sanitize(
                "password=hunter2 token=abc Bearer secret-token phone=13800138000 raw_payload={money}"
        );

        assertFalse(sanitized.contains("hunter2"));
        assertFalse(sanitized.contains("13800138000"));
        assertFalse(sanitized.contains("secret-token"));
        assertTrue(sanitized.contains("password=***"));
        assertTrue(sanitized.contains("raw_payload=***"));
    }
}
