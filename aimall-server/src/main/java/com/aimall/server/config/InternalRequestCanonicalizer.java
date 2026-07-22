package com.aimall.server.config;

import java.util.Arrays;
import java.util.stream.Collectors;

final class InternalRequestCanonicalizer {
    private InternalRequestCanonicalizer() {
    }

    static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return "";
        return Arrays.stream(rawQuery.split("&"))
                .filter(part -> !part.isEmpty())
                .sorted()
                .collect(Collectors.joining("&"));
    }
}
