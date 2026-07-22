package com.aimall.server.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.Map;

public record AiSessionRequest(
        @NotBlank @Size(max = 160) String sessionId,
        @Size(max = 64) String tenantId
) {
    public Map<String, Object> toPayload(long userId, Map<String, Object> authContext) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId);
        payload.put("tenantId", tenantId == null || tenantId.isBlank() ? "default" : tenantId);
        payload.put("userId", userId);
        payload.put("authContext", authContext);
        return payload;
    }
}
