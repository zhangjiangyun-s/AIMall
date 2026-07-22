package com.aimall.server.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record StockAdjustmentRequest(@NotNull @Min(-1000000) @Max(1000000) Integer delta) {
}
