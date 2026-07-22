package com.aimall.server.ai.dto;
import jakarta.validation.constraints.*;
public record KnowledgeParseResultRequest(@NotBlank @Size(max=64) String executionTaskId,@NotBlank @Size(max=128) String executionToken,@Size(max=1000) String parsedJsonPath,@Size(max=1000) String previewTextPath,@Min(0) Integer pageCount,@Min(0) Integer paragraphCount,@Min(0) Integer tableCount,@Min(0) Integer imageCount,@Min(0) Integer piiCount,@Pattern(regexp="LOW|MEDIUM|HIGH|CRITICAL") String promptRiskLevel,@NotBlank @Size(max=64) String status) {}
