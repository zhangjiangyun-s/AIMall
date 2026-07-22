package com.aimall.server.order.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record OrderPreviewRequest(
        @NotEmpty List<@Positive Long> cartItemIds,
        @Positive Long memberCouponId
) {
}
