package com.aimall.server.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LogisticsEventRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Z0-9_]+") String eventCode,
        @Size(max = 255) String location,
        @NotBlank @Size(max = 1000) String description
) {
}
