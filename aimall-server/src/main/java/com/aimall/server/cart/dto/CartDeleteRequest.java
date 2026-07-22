package com.aimall.server.cart.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CartDeleteRequest(@NotNull @Positive Long cartItemId) {
}
