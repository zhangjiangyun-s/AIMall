package com.aimall.server.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReconciliationEscalateRequest(
        @NotBlank @Size(max = 1000) String reason,
        @NotBlank @Size(max = 2000) String evidence
) {
}
