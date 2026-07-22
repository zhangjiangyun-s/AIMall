package com.aimall.server.service;

import com.aimall.server.entity.OmsCartItem;
import com.aimall.server.entity.PmsProduct;
import com.aimall.server.entity.PmsSkuStock;

import java.math.BigDecimal;
import java.util.List;
import com.aimall.server.money.MoneyPolicy;

public interface CartPricingService {

    PriceQuote quote(Long memberId, List<Long> cartItemIds);

    record PricedCartItem(
            OmsCartItem cartItem,
            PmsProduct product,
            PmsSkuStock sku,
            BigDecimal unitPrice,
            BigDecimal originalCartPrice,
            String priceRuleName,
            Long priceRuleId
    ) {
        public BigDecimal lineAmount() {
            return MoneyPolicy.storage(unitPrice.multiply(BigDecimal.valueOf(cartItem.getQuantity())));
        }

        public boolean priceChanged() {
            return originalCartPrice == null || originalCartPrice.compareTo(unitPrice) != 0;
        }
    }

    record PriceQuote(List<PricedCartItem> items, BigDecimal goodsAmount) {
        public List<OmsCartItem> cartItems() {
            return items.stream().map(PricedCartItem::cartItem).toList();
        }
    }
}
