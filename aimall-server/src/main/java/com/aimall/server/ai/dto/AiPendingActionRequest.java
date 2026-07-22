package com.aimall.server.ai.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.Map;

public record AiPendingActionRequest(
        @NotBlank @Size(max = 160) String sessionId,
        @Size(max = 64) String tenantId,
        @Min(1) Integer actionVersion
) {
    public Map<String, Object> toPayload(long userId, Map<String, Object> authContext) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId);
        payload.put("tenantId", tenantId == null || tenantId.isBlank() ? "default" : tenantId);
        payload.put("actionVersion", actionVersion == null ? 1 : actionVersion);
        payload.put("userId", userId);
        payload.put("authContext", authContext);
        return payload;
    }
}
