package com.aimall.server.ai.dto;
import jakarta.validation.constraints.*; import java.util.List;
public record KnowledgeChunkRetrieveRequest(@NotEmpty @Size(max=100) List<@Positive Long> chunkIds,@Positive Long categoryId,@Size(max=64) String sourceType) {}
