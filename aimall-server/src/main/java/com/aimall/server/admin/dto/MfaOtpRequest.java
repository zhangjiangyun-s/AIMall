package com.aimall.server.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record MfaOtpRequest(@NotBlank @Pattern(regexp = "\\d{6}") String otp) {
}
