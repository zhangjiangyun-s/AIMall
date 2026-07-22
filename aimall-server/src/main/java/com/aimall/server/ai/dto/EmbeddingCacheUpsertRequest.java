package com.aimall.server.ai.dto;
import jakarta.validation.constraints.*;
public record EmbeddingCacheUpsertRequest(@NotBlank @Pattern(regexp="[a-fA-F0-9]{32,128}") String contentHash,@NotBlank @Size(max=255) String embeddingModel,@NotBlank @Size(max=255) String embeddingId,@NotNull @Min(1) Integer vectorDimension,@NotNull @Min(0) Long retrievalEpoch) {}
