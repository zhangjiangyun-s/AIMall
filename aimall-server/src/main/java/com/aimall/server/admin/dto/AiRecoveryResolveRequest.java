package com.aimall.server.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record AiRecoveryResolveRequest(
        @NotNull Boolean succeeded,
        @Size(max = 1000) String note,
        Map<String, Object> result
) {
    public Map<String, Object> resolvedResult() {
        return result == null ? Map.of("recovered", true) : Map.copyOf(result);
    }
}
