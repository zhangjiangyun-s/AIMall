package com.aimall.server.payment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PaymentOrderRequest(@NotNull @Positive Long orderId) {
}
