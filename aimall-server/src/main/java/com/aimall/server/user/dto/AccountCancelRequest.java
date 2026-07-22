package com.aimall.server.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AccountCancelRequest(@NotBlank @Size(max = 72) String password) {
}
