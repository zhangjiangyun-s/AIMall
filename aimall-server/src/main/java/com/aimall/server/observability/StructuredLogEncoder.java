package com.aimall.server.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class StructuredLogEncoder extends EncoderBase<ILoggingEvent> {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public byte[] headerBytes() {
        return null;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        payload.put("level", event.getLevel().toString());
        payload.put("service", "aimall-server");
        payload.put("logger", event.getLoggerName());
        payload.put("traceId", event.getMDCPropertyMap().getOrDefault("traceId", ""));
        payload.put("message", SensitiveLogSanitizer.sanitize(event.getFormattedMessage()));
        if (event.getThrowableProxy() != null) {
            payload.put("exceptionType", event.getThrowableProxy().getClassName());
        }
        try {
            return (objectMapper.writeValueAsString(payload) + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8);
        } catch (Exception exception) {
            return ("{\"level\":\"ERROR\",\"service\":\"aimall-server\",\"message\":\"log encoding failed\"}"
                    + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public byte[] footerBytes() {
        return null;
    }
}
