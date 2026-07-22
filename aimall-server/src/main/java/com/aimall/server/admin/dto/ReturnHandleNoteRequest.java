package com.aimall.server.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReturnHandleNoteRequest(@NotBlank @Size(max = 1000) String handleNote) {
}
