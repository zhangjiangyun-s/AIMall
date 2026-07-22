package com.aimall.server.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PrivacyConsentRequest(@NotBlank @Size(max = 64) String version) {
}
