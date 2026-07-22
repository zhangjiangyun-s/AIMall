package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsCartItem;
import com.aimall.server.entity.PmsProduct;
import com.aimall.server.entity.PmsSkuStock;
import com.aimall.server.entity.UmsMember;
import com.aimall.server.mapper.OmsCartItemMapper;
import com.aimall.server.mapper.PmsProductMapper;
import com.aimall.server.mapper.PmsSkuStockMapper;
import com.aimall.server.mapper.UmsMemberMapper;
import com.aimall.server.service.CartService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class CartServiceImpl implements CartService {

    private final OmsCartItemMapper cartItemMapper;
    private final PmsProductMapper productMapper;
    private final PmsSkuStockMapper skuStockMapper;
    private final UmsMemberMapper memberMapper;

    public CartServiceImpl(
            OmsCartItemMapper cartItemMapper,
            PmsProductMapper productMapper,
            PmsSkuStockMapper skuStockMapper,
            UmsMemberMapper memberMapper
    ) {
        this.cartItemMapper = cartItemMapper;
        this.productMapper = productMapper;
        this.skuStockMapper = skuStockMapper;
        this.memberMapper = memberMapper;
    }

    @Override
    @Transactional
    public OmsCartItem add(Long memberId, Long productId, Long productSkuId, Integer quantity) {
        PmsProduct product = productMapper.selectById(productId);
        if (product == null || product.getDeleteStatus() == 1 || product.getPublishStatus() != 1) {
            throw new RuntimeException("商品不存在或已下架");
        }
        if (quantity == null || quantity <= 0 || quantity > 99) {
            throw new RuntimeException("商品数量必须在 1 到 99 之间");
        }

        PmsSkuStock sku = resolveSku(productId, productSkuId);
        Long resolvedSkuId = sku == null ? null : sku.getId();
        OmsCartItem existing = cartItemMapper.selectOne(
                new LambdaQueryWrapper<OmsCartItem>()
                        .eq(OmsCartItem::getMemberId, memberId)
                        .eq(OmsCartItem::getProductId, productId)
                        .eq(OmsCartItem::getDeleteStatus, 0)
                        .eq(resolvedSkuId != null, OmsCartItem::getProductSkuId, resolvedSkuId)
                        .isNull(resolvedSkuId == null, OmsCartItem::getProductSkuId)
        );
        int targetQuantity = quantity + (existing == null || existing.getQuantity() == null ? 0 : existing.getQuantity());
        int availableStock = availableStock(product, sku);
        if (targetQuantity > availableStock) {
            throw new RuntimeException("库存不足，当前最多可加入 " + availableStock + " 件");
        }
        if (existing != null) {
            incrementExisting(existing, quantity, availableStock, effectivePrice(product, sku));
            return findOwnedActiveItem(memberId, existing.getId());
        }

        UmsMember member = memberMapper.selectById(memberId);
        OmsCartItem item = new OmsCartItem();
        item.setMemberId(memberId);
        item.setMemberNickname(member == null ? "用户" : member.getNickname());
        item.setProductId(productId);
        item.setProductSkuId(sku == null ? null : sku.getId());
        item.setProductSkuCode(sku == null ? null : sku.getSkuCode());
        item.setQuantity(quantity);
        item.setPrice(effectivePrice(product, sku));
        item.setProductPic(sku != null && sku.getPic() != null ? sku.getPic() : product.getPic());
        item.setProductName(product.getName());
        item.setProductSubTitle(product.getSubTitle());
        item.setProductCategoryName(product.getProductCategoryName());
        item.setProductBrand(product.getBrandName());
        item.setProductSn(product.getProductSn());
        item.setProductAttr(sku == null ? null : sku.getSpData());
        item.setCreateDate(LocalDateTime.now());
        item.setModifyDate(LocalDateTime.now());
        item.setDeleteStatus(0);
        if (cartItemMapper.insertIgnore(item) == 1) {
            return item;
        }
        OmsCartItem concurrentExisting = cartItemMapper.selectOne(
                new LambdaQueryWrapper<OmsCartItem>()
                        .eq(OmsCartItem::getMemberId, memberId)
                        .eq(OmsCartItem::getProductId, productId)
                        .eq(OmsCartItem::getDeleteStatus, 0)
                        .eq(resolvedSkuId != null, OmsCartItem::getProductSkuId, resolvedSkuId)
                        .isNull(resolvedSkuId == null, OmsCartItem::getProductSkuId)
        );
        if (concurrentExisting == null) {
            throw new RuntimeException("购物车写入冲突，请重试");
        }
        incrementExisting(concurrentExisting, quantity, availableStock, item.getPrice());
        return findOwnedActiveItem(memberId, concurrentExisting.getId());
    }

    @Override
    public List<OmsCartItem> list(Long memberId) {
        return cartItemMapper.selectList(
                new LambdaQueryWrapper<OmsCartItem>()
                        .eq(OmsCartItem::getMemberId, memberId)
                        .eq(OmsCartItem::getDeleteStatus, 0)
                        .orderByDesc(OmsCartItem::getId)
        );
    }

    @Override
    public void updateQuantity(Long memberId, Long id, Integer quantity) {
        OmsCartItem item = findOwnedActiveItem(memberId, id);
        if (item == null) {
            throw new RuntimeException("购物车商品不存在");
        }
        if (quantity == null || quantity <= 0 || quantity > 99) {
            throw new RuntimeException("商品数量必须在 1 到 99 之间");
        }
        PmsProduct product = productMapper.selectById(item.getProductId());
        if (product == null || product.getDeleteStatus() == 1 || product.getPublishStatus() != 1) {
            throw new RuntimeException("商品不存在或已下架");
        }
        PmsSkuStock sku = item.getProductSkuId() == null ? null : resolveSku(item.getProductId(), item.getProductSkuId());
        int availableStock = availableStock(product, sku);
        if (quantity > availableStock) {
            throw new RuntimeException("库存不足，当前最多可购买 " + availableStock + " 件");
        }
        int updated = cartItemMapper.update(
                null,
                new UpdateWrapper<OmsCartItem>()
                        .eq("id", id)
                        .eq("member_id", memberId)
                        .eq("delete_status", 0)
                        .set("quantity", quantity)
                        .set("modify_date", LocalDateTime.now())
        );
        if (updated != 1) {
            throw new RuntimeException("购物车商品不存在");
        }
    }

    @Override
    public void delete(Long memberId, Long id) {
        int updated = cartItemMapper.update(
                null,
                new UpdateWrapper<OmsCartItem>()
                        .eq("id", id)
                        .eq("member_id", memberId)
                        .eq("delete_status", 0)
                        .set("delete_status", 1)
                        .set("modify_date", LocalDateTime.now())
        );
        if (updated != 1) {
            throw new RuntimeException("购物车商品不存在");
        }
    }

    private OmsCartItem findOwnedActiveItem(Long memberId, Long id) {
        return cartItemMapper.selectOne(
                new LambdaQueryWrapper<OmsCartItem>()
                        .eq(OmsCartItem::getId, id)
                        .eq(OmsCartItem::getMemberId, memberId)
                        .eq(OmsCartItem::getDeleteStatus, 0)
        );
    }

    private void incrementExisting(OmsCartItem item, int delta, int availableStock, java.math.BigDecimal currentPrice) {
        int maxQuantity = Math.min(99, availableStock);
        if (cartItemMapper.incrementQuantity(item.getId(), item.getMemberId(), delta, maxQuantity, currentPrice) != 1) {
            throw new RuntimeException("商品数量超过库存或购物车上限");
        }
    }

    private PmsSkuStock resolveSku(Long productId, Long productSkuId) {
        if (productSkuId == null) {
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
        PmsSkuStock sku = skuStockMapper.selectById(productSkuId);
        if (sku == null || !productId.equals(sku.getProductId()) || sku.getStatus() == null || sku.getStatus() != 1) {
            throw new RuntimeException("商品规格不存在");
        }
        return sku;
    }

    private int availableStock(PmsProduct product, PmsSkuStock sku) {
        if (sku == null) {
            int stock = product.getStock() == null ? 0 : product.getStock();
            int locked = product.getLockStock() == null ? 0 : product.getLockStock();
            return Math.max(0, stock - locked);
        }
        int stock = sku.getStock() == null ? 0 : sku.getStock();
        int locked = sku.getLockStock() == null ? 0 : sku.getLockStock();
        return Math.max(0, stock - locked);
    }

    private java.math.BigDecimal effectivePrice(PmsProduct product, PmsSkuStock sku) {
        if (sku != null) {
            return sku.getPromotionPrice() == null ? sku.getPrice() : sku.getPromotionPrice();
        }
        return product.getPromotionPrice() == null ? product.getPrice() : product.getPromotionPrice();
    }
}
