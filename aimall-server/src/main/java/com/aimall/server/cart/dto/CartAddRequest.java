package com.aimall.server.cart.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CartAddRequest(
        @NotNull @Positive Long productId,
        @Positive Long productSkuId,
        @Min(1) @Max(99) Integer quantity
) {
    public int resolvedQuantity() {
        return quantity == null ? 1 : quantity;
    }
}
