package com.aimall.server.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.Map;

public record AiChatRequest(
        @NotBlank @Size(max = 4000) String message,
        @NotBlank @Size(max = 160) String sessionId,
        @Size(max = 64) String tenantId,
        @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String traceId,
        @Valid PageContext pageContext
) {
    public Map<String, Object> toPayload(long userId, String resolvedTraceId, Map<String, Object> authContext) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("tenantId", tenantId == null || tenantId.isBlank() ? "default" : tenantId);
        payload.put("message", message);
        payload.put("sessionId", sessionId);
        payload.put("traceId", resolvedTraceId);
        if (pageContext != null) payload.put("pageContext", pageContext.toPayload());
        payload.put("authContext", authContext);
        return payload;
    }

    public record PageContext(
            @Size(max = 64) String pageType,
            @Positive Long productId,
            @Positive Long orderId,
            @Size(max = 200) String keyword,
            @Positive Long categoryId,
            @Positive Integer cartItemCount
    ) {
        public Map<String, Object> toPayload() {
            Map<String, Object> value = new LinkedHashMap<>();
            if (pageType != null) value.put("pageType", pageType);
            if (productId != null) value.put("productId", productId);
            if (orderId != null) value.put("orderId", orderId);
            if (keyword != null) value.put("keyword", keyword);
            if (categoryId != null) value.put("categoryId", categoryId);
            if (cartItemCount != null) value.put("cartItemCount", cartItemCount);
            return value;
        }
    }
}
