package com.aimall.server.service.impl;

import com.aimall.server.entity.PmsProduct;
import com.aimall.server.admin.dto.SkuUpdateRequest;
import com.aimall.server.entity.PmsSkuStock;
import com.aimall.server.mapper.PmsProductImageMapper;
import com.aimall.server.mapper.PmsProductMapper;
import com.aimall.server.mapper.PmsSkuStockMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class AdminProductCatalogServiceImplTest {

    @Test
    void updateUsesAdminFieldCasWithoutWritingBackLockStock() {
        PmsProductMapper productMapper = mock(PmsProductMapper.class);
        PmsSkuStockMapper skuMapper = mock(PmsSkuStockMapper.class);
        PmsProduct product = new PmsProduct();
        product.setId(1L);
        product.setDeleteStatus(0);
        PmsSkuStock existing = sku(10);
        PmsSkuStock refreshed = sku(10);
        refreshed.setLockStock(3);
        when(productMapper.selectById(1L)).thenReturn(product);
        when(skuMapper.selectById(2L)).thenReturn(existing, refreshed);
        when(skuMapper.updateAdminFields(any(PmsSkuStockMapper.AdminUpdate.class))).thenReturn(1);
        ProductPriceHistoryService historyService = mock(ProductPriceHistoryService.class);
        AdminProductCatalogServiceImpl service = new AdminProductCatalogServiceImpl(
                productMapper, skuMapper, mock(PmsProductImageMapper.class), new ObjectMapper(), historyService);
        SkuUpdateRequest patch = new SkuUpdateRequest();
        patch.setPrice(new BigDecimal("9.00"));

        PmsSkuStock result = service.updateSku(1L, 2L, patch);

        assertSame(refreshed, result);
        ArgumentCaptor<PmsSkuStockMapper.AdminUpdate> updateCaptor = ArgumentCaptor.forClass(PmsSkuStockMapper.AdminUpdate.class);
        verify(skuMapper).updateAdminFields(updateCaptor.capture());
        org.junit.jupiter.api.Assertions.assertTrue(updateCaptor.getValue().updatePrice());
        org.junit.jupiter.api.Assertions.assertFalse(updateCaptor.getValue().updateLowStock());
        verify(skuMapper, never()).updateById(any(PmsSkuStock.class));
        verify(historyService).recordSku(1L, 2L, "BASE", new BigDecimal("10.0000"),
                new BigDecimal("9.0000"), "ADMIN_UPDATE");
    }

    @Test
    void explicitNullClearsNullableSkuFields() {
        PmsProductMapper productMapper = mock(PmsProductMapper.class);
        PmsSkuStockMapper skuMapper = mock(PmsSkuStockMapper.class);
        PmsProduct product = new PmsProduct();
        product.setId(1L);
        product.setDeleteStatus(0);
        PmsSkuStock existing = sku(10);
        existing.setPromotionPrice(new BigDecimal("8.00"));
        PmsSkuStock refreshed = sku(10);
        when(productMapper.selectById(1L)).thenReturn(product);
        when(skuMapper.selectById(2L)).thenReturn(existing, refreshed);
        when(skuMapper.updateAdminFields(any(PmsSkuStockMapper.AdminUpdate.class))).thenReturn(1);
        AdminProductCatalogServiceImpl service = new AdminProductCatalogServiceImpl(
                productMapper, skuMapper, mock(PmsProductImageMapper.class), new ObjectMapper()
        );
        SkuUpdateRequest patch = new SkuUpdateRequest();
        patch.setPromotionPrice(null);

        service.updateSku(1L, 2L, patch);

        ArgumentCaptor<PmsSkuStockMapper.AdminUpdate> captor = ArgumentCaptor.forClass(PmsSkuStockMapper.AdminUpdate.class);
        verify(skuMapper).updateAdminFields(captor.capture());
        org.junit.jupiter.api.Assertions.assertTrue(captor.getValue().updatePromotionPrice());
        assertNull(captor.getValue().promotionPrice());
    }

    private PmsSkuStock sku(int stock) {
        PmsSkuStock sku = new PmsSkuStock();
        sku.setId(2L);
        sku.setProductId(1L);
        sku.setSkuCode("SKU-2");
        sku.setPrice(new BigDecimal("10.00"));
        sku.setStock(stock);
        sku.setLowStock(1);
        sku.setLockStock(0);
        sku.setSale(0);
        sku.setStatus(1);
        return sku;
    }
}
