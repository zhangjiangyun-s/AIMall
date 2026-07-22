package com.aimall.server.cart.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CartUpdateRequest(
        @NotNull @Positive Long cartItemId,
        @NotNull @Min(1) @Max(99) Integer quantity
) {
}
