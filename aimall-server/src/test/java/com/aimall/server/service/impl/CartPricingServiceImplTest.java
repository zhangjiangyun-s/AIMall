package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsCartItem;
import com.aimall.server.entity.PmsProduct;
import com.aimall.server.entity.PmsSkuStock;
import com.aimall.server.mapper.OmsCartItemMapper;
import com.aimall.server.mapper.PmsProductMapper;
import com.aimall.server.mapper.PmsSkuStockMapper;
import com.aimall.server.service.CartPricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CartPricingServiceImplTest {

    private OmsCartItemMapper cartItemMapper;
    private PmsProductMapper productMapper;
    private PmsSkuStockMapper skuStockMapper;
    private CartPricingService pricingService;

    @BeforeEach
    void setUp() {
        cartItemMapper = mock(OmsCartItemMapper.class);
        productMapper = mock(PmsProductMapper.class);
        skuStockMapper = mock(PmsSkuStockMapper.class);
        pricingService = new CartPricingServiceImpl(cartItemMapper, productMapper, skuStockMapper);
    }

    @Test
    void quoteUsesCurrentSkuPriceInsteadOfCartSnapshot() {
        OmsCartItem cartItem = cartItem(10L, 100L, 200L, new BigDecimal("3999.00"), 2);
        PmsProduct product = visibleProduct(100L);
        PmsSkuStock sku = sku(200L, 100L, new BigDecimal("3799.00"), new BigDecimal("3599.00"), 10, 0);
        when(cartItemMapper.selectBatchIds(List.of(10L))).thenReturn(List.of(cartItem));
        when(productMapper.selectById(100L)).thenReturn(product);
        when(skuStockMapper.selectById(200L)).thenReturn(sku);

        CartPricingService.PriceQuote quote = pricingService.quote(1L, List.of(10L));

        assertEquals(new BigDecimal("7198.0000"), quote.goodsAmount());
        assertEquals(new BigDecimal("3599.0000"), quote.items().get(0).unitPrice());
        assertEquals(true, quote.items().get(0).priceChanged());
    }

    @Test
    void quoteRejectsMissingSkuWhenProductHasSpecifications() {
        OmsCartItem cartItem = cartItem(10L, 100L, null, new BigDecimal("100.00"), 1);
        when(cartItemMapper.selectBatchIds(List.of(10L))).thenReturn(List.of(cartItem));
        when(productMapper.selectById(100L)).thenReturn(visibleProduct(100L));
        when(skuStockMapper.countAllByProductId(100L)).thenReturn(1L);
        when(skuStockMapper.selectCount(any())).thenReturn(1L);

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> pricingService.quote(1L, List.of(10L))
        );

        assertEquals("请选择商品规格", exception.getMessage());
    }

    @Test
    void quoteRejectsProductWhenAllHistoricalSkusAreDisabled() {
        OmsCartItem cartItem = cartItem(10L, 100L, null, new BigDecimal("100.00"), 1);
        when(cartItemMapper.selectBatchIds(List.of(10L))).thenReturn(List.of(cartItem));
        when(productMapper.selectById(100L)).thenReturn(visibleProduct(100L));
        when(skuStockMapper.countAllByProductId(100L)).thenReturn(1L);
        when(skuStockMapper.selectCount(any())).thenReturn(0L);

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> pricingService.quote(1L, List.of(10L))
        );

        assertEquals("商品规格已全部停用", exception.getMessage());
    }

    @Test
    void quoteRejectsDuplicateCartItemIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> pricingService.quote(1L, List.of(10L, 10L))
        );
    }

    private OmsCartItem cartItem(Long id, Long productId, Long skuId, BigDecimal price, int quantity) {
        OmsCartItem item = new OmsCartItem();
        item.setId(id);
        item.setMemberId(1L);
        item.setProductId(productId);
        item.setProductSkuId(skuId);
        item.setPrice(price);
        item.setQuantity(quantity);
        item.setDeleteStatus(0);
        return item;
    }

    private PmsProduct visibleProduct(Long id) {
        PmsProduct product = new PmsProduct();
        product.setId(id);
        product.setName("轻薄笔记本");
        product.setDeleteStatus(0);
        product.setPublishStatus(1);
        product.setVerifyStatus(1);
        product.setStock(10);
        product.setLockStock(0);
        return product;
    }

    private PmsSkuStock sku(
            Long id,
            Long productId,
            BigDecimal price,
            BigDecimal promotionPrice,
            int stock,
            int lockStock
    ) {
        PmsSkuStock sku = new PmsSkuStock();
        sku.setId(id);
        sku.setProductId(productId);
        sku.setPrice(price);
        sku.setPromotionPrice(promotionPrice);
        sku.setStock(stock);
        sku.setLockStock(lockStock);
        sku.setStatus(1);
        return sku;
    }
}
