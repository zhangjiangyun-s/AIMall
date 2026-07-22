package com.aimall.server.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserLoginRequest(
        @NotBlank @Size(min = 3, max = 64) String username,
        @NotBlank @Size(min = 1, max = 72) String password
) {
}
