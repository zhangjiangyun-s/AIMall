package com.aimall.server.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ShipmentRequest(
        @NotBlank @Size(max = 100) String deliveryCompany,
        @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]*") String carrierCode,
        @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String deliverySn
) {
    public String resolvedCarrierCode() {
        return carrierCode == null || carrierCode.isBlank()
                ? deliveryCompany.trim().toUpperCase() : carrierCode.trim();
    }
}
