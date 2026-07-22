package com.aimall.server.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.Map;

public record AiFeedbackRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String traceId,
        @NotBlank @Pattern(regexp = "LIKE|DISLIKE|CORRECTION") String feedbackType,
        @NotBlank @Size(max = 160) String sessionId,
        @Size(max = 2000) String userComment,
        @Size(max = 4000) String correctSnippet
) {
    public Map<String, Object> toPayload(long userId, Map<String, Object> authContext) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("traceId", traceId);
        payload.put("feedbackType", feedbackType);
        payload.put("sessionId", sessionId);
        payload.put("userId", userId);
        if (userComment != null) payload.put("userComment", userComment);
        if (correctSnippet != null) payload.put("correctSnippet", correctSnippet);
        payload.put("authContext", authContext);
        return payload;
    }
}
