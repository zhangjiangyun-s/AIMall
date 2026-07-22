package com.aimall.server.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReturnLogisticsRequest(
        @NotBlank @Size(max = 64) String carrier,
        @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String trackingNo
) {
}
