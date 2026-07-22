package com.aimall.server.observability;

import java.util.regex.Pattern;

public final class SensitiveLogSanitizer {
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(password|token|authorization|secret|phone|address|raw[_-]?payload)\\s*[=:]\\s*([^,\\s}]+)"
    );
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+={0,2}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1\\d{10}(?!\\d)");

    private SensitiveLogSanitizer() {
    }

    public static String sanitize(String message) {
        if (message == null) {
            return "";
        }
        String sanitized = KEY_VALUE.matcher(message).replaceAll("$1=***");
        sanitized = BEARER.matcher(sanitized).replaceAll("Bearer ***");
        return PHONE.matcher(sanitized).replaceAll("***PHONE***");
    }
}
