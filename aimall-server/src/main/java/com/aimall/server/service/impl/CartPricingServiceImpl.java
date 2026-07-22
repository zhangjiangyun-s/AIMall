package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsCartItem;
import com.aimall.server.entity.PmsProduct;
import com.aimall.server.entity.PmsSkuStock;
import com.aimall.server.mapper.OmsCartItemMapper;
import com.aimall.server.mapper.PmsProductMapper;
import com.aimall.server.mapper.PmsSkuStockMapper;
import com.aimall.server.service.CartPricingService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import com.aimall.server.money.MoneyPolicy;

@Service
public class CartPricingServiceImpl implements CartPricingService {

    private final OmsCartItemMapper cartItemMapper;
    private final PmsProductMapper productMapper;
    private final PmsSkuStockMapper skuStockMapper;
    private final ProductPriceRuleService priceRuleService;

    public CartPricingServiceImpl(
            OmsCartItemMapper cartItemMapper,
            PmsProductMapper productMapper,
            PmsSkuStockMapper skuStockMapper
    ) {
        this(cartItemMapper, productMapper, skuStockMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CartPricingServiceImpl(
            OmsCartItemMapper cartItemMapper,
            PmsProductMapper productMapper,
            PmsSkuStockMapper skuStockMapper,
            ProductPriceRuleService priceRuleService
    ) {
        this.cartItemMapper = cartItemMapper;
        this.productMapper = productMapper;
        this.skuStockMapper = skuStockMapper;
        this.priceRuleService = priceRuleService;
    }

    @Override
    public PriceQuote quote(Long memberId, List<Long> cartItemIds) {
        if (cartItemIds == null || cartItemIds.isEmpty()) {
            throw new RuntimeException("请选择购物车商品");
        }
        List<Long> distinctIds = new ArrayList<>(new LinkedHashSet<>(cartItemIds));
        if (distinctIds.size() != cartItemIds.size()) {
            throw new IllegalArgumentException("购物车商品不能重复提交");
        }
        List<OmsCartItem> cartItems = cartItemMapper.selectBatchIds(distinctIds);
        if (cartItems.size() != distinctIds.size()) {
            throw new RuntimeException("部分购物车商品不存在");
        }

        List<PricedCartItem> pricedItems = new ArrayList<>();
        BigDecimal goodsAmount = BigDecimal.ZERO;
        for (OmsCartItem cartItem : cartItems) {
            validateOwnedCartItem(memberId, cartItem);
            PmsProduct product = requireVisibleProduct(cartItem.getProductId());
            PmsSkuStock sku = resolveRequiredSku(product.getId(), cartItem.getProductSkuId());
            validateQuantityAndStock(cartItem, product, sku);
            BigDecimal baselinePrice = currentPrice(product, sku);
            ProductPriceRuleService.PriceResolution resolution = priceRuleService == null
                    ? new ProductPriceRuleService.PriceResolution(baselinePrice, null, null)
                    : priceRuleService.resolve(memberId, product.getId(), sku == null ? null : sku.getId(),
                            baselinePrice, cartItem.getQuantity());
            PricedCartItem pricedItem = new PricedCartItem(
                    cartItem, product, sku, resolution.price(), cartItem.getPrice(),
                    resolution.ruleName(), resolution.ruleId()
            );
            pricedItems.add(pricedItem);
            goodsAmount = MoneyPolicy.storage(goodsAmount.add(pricedItem.lineAmount()));
        }
        return new PriceQuote(List.copyOf(pricedItems), MoneyPolicy.storage(goodsAmount));
    }

    private void validateOwnedCartItem(Long memberId, OmsCartItem cartItem) {
        if (cartItem == null
                || !memberId.equals(cartItem.getMemberId())
                || cartItem.getDeleteStatus() == null
                || cartItem.getDeleteStatus() != 0) {
            throw new RuntimeException("购物车商品不可结算");
        }
    }

    private PmsProduct requireVisibleProduct(Long productId) {
        PmsProduct product = productMapper.selectById(productId);
        if (product == null
                || product.getDeleteStatus() == null
                || product.getDeleteStatus() != 0
                || product.getPublishStatus() == null
                || product.getPublishStatus() != 1
                || product.getVerifyStatus() == null
                || product.getVerifyStatus() != 1) {
            throw new RuntimeException("商品不存在或已下架");
        }
        return product;
    }

    private PmsSkuStock resolveRequiredSku(Long productId, Long skuId) {
        if (skuId == null) {
            long skuCount = skuStockMapper.countAllByProductId(productId);
            if (skuCount > 0) {
                Long enabledCount = skuStockMapper.selectCount(
                        new LambdaQueryWrapper<PmsSkuStock>()
                                .eq(PmsSkuStock::getProductId, productId)
                                .eq(PmsSkuStock::getStatus, 1)
                );
                if (enabledCount == null || enabledCount == 0) {
                    throw new RuntimeException("商品规格已全部停用");
                }
                throw new RuntimeException("请选择商品规格");
            }
            return null;
        }
        PmsSkuStock sku = skuStockMapper.selectById(skuId);
        if (sku == null || !productId.equals(sku.getProductId()) || sku.getStatus() == null || sku.getStatus() != 1) {
            throw new RuntimeException("商品规格不存在");
        }
        return sku;
    }

    private void validateQuantityAndStock(OmsCartItem cartItem, PmsProduct product, PmsSkuStock sku) {
        int quantity = cartItem.getQuantity() == null ? 0 : cartItem.getQuantity();
        if (quantity <= 0 || quantity > 99) {
            throw new RuntimeException("商品数量必须在 1 到 99 之间");
        }
        int available = sku == null
                ? safe(product.getStock()) - safe(product.getLockStock())
                : safe(sku.getStock()) - safe(sku.getLockStock());
        if (available < quantity) {
            throw new RuntimeException("商品库存不足");
        }
    }

    private BigDecimal currentPrice(PmsProduct product, PmsSkuStock sku) {
        BigDecimal price = sku == null
                ? firstNonNull(product.getPromotionPrice(), product.getPrice())
                : firstNonNull(sku.getPromotionPrice(), sku.getPrice());
        if (price == null || price.compareTo(BigDecimal.ZERO) < 0) {
            throw new RuntimeException("商品价格配置异常");
        }
        return price;
    }

    private BigDecimal firstNonNull(BigDecimal preferred, BigDecimal fallback) {
        return preferred == null ? fallback : preferred;
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }
}
