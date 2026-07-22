package com.aimall.server.admin.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductUpdateRequestTest {
    @Test
    void explicitJsonNullIsDifferentFromMissingField() throws Exception {
        ProductUpdateRequest request = new ObjectMapper().readValue(
                "{\"promotionPrice\":null,\"originalPrice\":null}",
                ProductUpdateRequest.class
        );

        assertTrue(request.isPromotionPricePresent());
        assertTrue(request.isOriginalPricePresent());
        assertNull(request.getPromotionPrice());
        assertNull(request.getOriginalPrice());
    }
}
