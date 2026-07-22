package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsCartItem;
import com.aimall.server.entity.PmsProduct;
import com.aimall.server.mapper.OmsCartItemMapper;
import com.aimall.server.mapper.PmsProductMapper;
import com.aimall.server.mapper.PmsSkuStockMapper;
import com.aimall.server.mapper.UmsMemberMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CartServiceImplTest {

    private OmsCartItemMapper cartItemMapper;
    private PmsProductMapper productMapper;
    private PmsSkuStockMapper skuStockMapper;
    private CartServiceImpl cartService;

    @BeforeEach
    void setUp() {
        cartItemMapper = mock(OmsCartItemMapper.class);
        productMapper = mock(PmsProductMapper.class);
        skuStockMapper = mock(PmsSkuStockMapper.class);
        cartService = new CartServiceImpl(
                cartItemMapper,
                productMapper,
                skuStockMapper,
                mock(UmsMemberMapper.class)
        );
    }

    @Test
    void updateQuantityRejectsCartItemNotOwnedByCurrentMember() {
        when(cartItemMapper.selectOne(any())).thenReturn(null);

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> cartService.updateQuantity(200L, 10L, 2)
        );

        assertEquals("购物车商品不存在", exception.getMessage());
        verify(cartItemMapper, never()).update(isNull(OmsCartItem.class), any(Wrapper.class));
    }

    @Test
    void deleteRejectsCartItemNotOwnedByCurrentMember() {
        when(cartItemMapper.update(isNull(OmsCartItem.class), any(Wrapper.class))).thenReturn(0);

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> cartService.delete(200L, 10L)
        );

        assertEquals("购物车商品不存在", exception.getMessage());
        verify(cartItemMapper).update(isNull(OmsCartItem.class), any(Wrapper.class));
    }

    @Test
    void updateQuantityUpdatesOwnedCartItem() {
        OmsCartItem item = new OmsCartItem();
        item.setId(10L);
        item.setMemberId(100L);
        item.setDeleteStatus(0);
        item.setQuantity(1);
        when(cartItemMapper.selectOne(any())).thenReturn(item);
        PmsProduct product = new PmsProduct();
        product.setId(20L);
        product.setDeleteStatus(0);
        product.setPublishStatus(1);
        product.setStock(10);
        item.setProductId(product.getId());
        when(productMapper.selectById(product.getId())).thenReturn(product);
        when(cartItemMapper.update(isNull(OmsCartItem.class), any(Wrapper.class))).thenReturn(1);

        cartService.updateQuantity(100L, 10L, 3);

        verify(cartItemMapper).update(isNull(OmsCartItem.class), any(Wrapper.class));
    }

    @Test
    void productWithOnlyDisabledSkusNeverFallsBackToSpuInventory() {
        PmsProduct product = new PmsProduct();
        product.setId(20L);
        product.setDeleteStatus(0);
        product.setPublishStatus(1);
        product.setStock(99);
        when(productMapper.selectById(20L)).thenReturn(product);
        when(skuStockMapper.countAllByProductId(20L)).thenReturn(1L);
        when(skuStockMapper.selectCount(any())).thenReturn(0L);

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> cartService.add(100L, 20L, null, 1)
        );

        assertEquals("商品规格已全部停用", exception.getMessage());
    }
}
