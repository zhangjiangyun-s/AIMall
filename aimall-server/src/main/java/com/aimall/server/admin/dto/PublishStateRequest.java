package com.aimall.server.admin.dto;

import jakarta.validation.constraints.NotNull;

public record PublishStateRequest(@NotNull Boolean published) {
}
