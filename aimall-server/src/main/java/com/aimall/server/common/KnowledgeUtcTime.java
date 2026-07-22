package com.aimall.server.common;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class KnowledgeUtcTime {
    private static final String ENV_NAME = "AIMALL_KNOWLEDGE_SOURCE_TIME_ZONE";
    private static final ZoneId SOURCE_ZONE = resolveSourceZone();

    private KnowledgeUtcTime() {
    }

    public static String format(LocalDateTime value) {
        return format(value, SOURCE_ZONE);
    }

    static String format(LocalDateTime value, ZoneId sourceZone) {
        if (value == null) {
            return "";
        }
        return DateTimeFormatter.ISO_INSTANT.format(value.atZone(sourceZone).withZoneSameInstant(ZoneOffset.UTC));
    }

    private static ZoneId resolveSourceZone() {
        String configured = System.getenv(ENV_NAME);
        if (configured == null || configured.isBlank()) {
            configured = "Asia/Shanghai";
        }
        try {
            return ZoneId.of(configured.trim());
        } catch (DateTimeException exception) {
            throw new IllegalStateException(ENV_NAME + " is not a valid time zone: " + configured, exception);
        }
    }
}
