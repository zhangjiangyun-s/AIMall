package com.aimall.server.ai.dto;
import jakarta.validation.constraints.*;
public record VectorDeletionCompleteRequest(@NotBlank @Size(max=128) String claimToken) {}
