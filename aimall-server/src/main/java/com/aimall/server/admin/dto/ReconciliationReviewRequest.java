package com.aimall.server.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReconciliationReviewRequest(
        boolean approved,
        @NotBlank @Size(max = 64) String approvalNo,
        @NotBlank @Size(max = 1000) String note
) {
}
