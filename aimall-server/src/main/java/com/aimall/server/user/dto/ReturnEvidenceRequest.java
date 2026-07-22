package com.aimall.server.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReturnEvidenceRequest(
        @NotBlank @Pattern(regexp = "IMAGE|VIDEO") String mediaType,
        @NotBlank @Size(max = 1000) String mediaUrl
) {
}
