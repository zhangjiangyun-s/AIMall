package com.aimall.server.user.dto;

import com.aimall.server.service.ReturnApplyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ReturnApplyRequest(
        @NotNull @Positive Long orderId,
        @NotBlank @Size(max = 255) String reason,
        @Size(max = 2000) String description,
        @NotEmpty List<@Valid ReturnItem> items,
        @Pattern(regexp = "RETURN_REFUND|REFUND_ONLY") String type
) {
    public List<ReturnApplyService.ReturnItemRequest> serviceItems() {
        return items.stream().map(item ->
                new ReturnApplyService.ReturnItemRequest(item.orderItemId(), item.quantity())).toList();
    }

    public record ReturnItem(
            @NotNull @Positive Long orderItemId,
            @NotNull @Min(1) @Max(999) Integer quantity
    ) {
    }
}
