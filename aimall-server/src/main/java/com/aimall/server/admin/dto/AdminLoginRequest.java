package com.aimall.server.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminLoginRequest(
        @NotBlank @Size(min = 3, max = 64) String username,
        @NotBlank @Size(max = 72) String password,
        @Pattern(regexp = "\\d{6}") String otp
) {
}
