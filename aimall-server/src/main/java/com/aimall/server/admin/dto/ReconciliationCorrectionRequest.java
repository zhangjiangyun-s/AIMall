package com.aimall.server.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReconciliationCorrectionRequest(
        @NotBlank @Pattern(regexp = "PAYMENT_STATE|PAYMENT_AMOUNT|REFUND_STATE|REFUND_AMOUNT|EVIDENCE_LINK") String correctionType,
        @NotBlank @Size(max = 1000) String reason,
        @NotBlank @Size(max = 2000) String evidence,
        @NotBlank @Size(max = 4000) String originalValueJson,
        @NotBlank @Size(max = 4000) String proposedValueJson
) {
}
