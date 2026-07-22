package com.aimall.server.money;

import com.aimall.server.entity.PmsProduct;
import com.aimall.server.entity.PmsSkuStock;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyPolicyTest {
    @Test
    void settlementUsesFourDecimalHalfUpAndChannelUsesCurrencyScale() {
        assertEquals(new BigDecimal("1.2345"), MoneyPolicy.storage(new BigDecimal("1.23445")));
        assertEquals(new BigDecimal("1.24"), MoneyPolicy.channel(new BigDecimal("1.235"), 2));
        assertThrows(IllegalArgumentException.class, () -> MoneyPolicy.channel(BigDecimal.ONE, 5));
    }

    @Test
    void finalAllocationAbsorbsRoundingRemainderExactly() {
        List<BigDecimal> allocated = MoneyPolicy.allocate(new BigDecimal("10.0000"),
                List.of(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
        assertEquals(List.of(new BigDecimal("3.3333"), new BigDecimal("3.3333"), new BigDecimal("3.3334")), allocated);
        assertEquals(new BigDecimal("10.0000"), allocated.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void productAndSkuDualWritePreciseAndLegacyPrices() {
        PmsProduct product = new PmsProduct();
        product.setPrice(new BigDecimal("12.3456"));
        PmsSkuStock sku = new PmsSkuStock();
        sku.setPrice(new BigDecimal("9.8765"));

        assertEquals(new BigDecimal("12.3456"), product.getPrice());
        assertEquals(new BigDecimal("12.35"), product.getLegacyPrice());
        assertEquals(new BigDecimal("9.8765"), sku.getPrice());
        assertEquals(new BigDecimal("9.88"), sku.getLegacyPrice());
    }
}
