package com.aimall.server.ai.dto;
import jakarta.validation.constraints.*;
public record KnowledgeTaskEventRequest(@NotBlank @Size(max=128) String executionToken,@NotBlank @Size(max=64) String eventType,@NotBlank @Size(max=255) String title,@Size(max=2000) String detail,@Min(0) Integer progressCurrent,@Min(0) Integer progressTotal,Boolean ok,@Size(max=100) String errorCode,@Size(max=1000) String suggestion) {}
