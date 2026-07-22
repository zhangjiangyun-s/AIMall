package com.aimall.server.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReturnInspectionRequest(
        @NotNull Boolean accepted,
        @NotBlank @Size(max = 1000) String note
) {
}
