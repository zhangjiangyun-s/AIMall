package com.aimall.server.ai.dto;
import jakarta.validation.constraints.*;
public record VectorDeletionClaimRequest(@Min(1) @Max(500) Integer limit){public int resolvedLimit(){return limit==null?100:limit;}}
