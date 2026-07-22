package com.aimall.server.ai.dto;
import jakarta.validation.constraints.*;
public record VectorDeletionFailRequest(@NotBlank @Size(max=128) String claimToken,@Size(max=1000) String errorMessage) {}
