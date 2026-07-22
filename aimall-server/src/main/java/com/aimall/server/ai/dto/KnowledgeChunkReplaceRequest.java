package com.aimall.server.ai.dto;
import jakarta.validation.Valid; import jakarta.validation.constraints.*; import java.util.List;
public record KnowledgeChunkReplaceRequest(@NotBlank @Size(max=128) String executionToken,@NotEmpty List<@Valid KnowledgeChunkPayload> chunks,@Min(0) Integer piiCount,@Pattern(regexp="LOW|MEDIUM|HIGH|CRITICAL") String promptRiskLevel,@Size(max=64) String versionStatus,@Size(max=64) String docStatus) {}
