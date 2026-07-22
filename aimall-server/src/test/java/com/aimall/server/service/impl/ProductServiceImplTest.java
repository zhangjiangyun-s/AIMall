package com.aimall.server.service.impl;

import com.aimall.server.entity.PmsProduct;
import com.aimall.server.mapper.PmsProductCategoryMapper;
import com.aimall.server.mapper.PmsProductMapper;
import com.aimall.server.mapper.PmsSkuStockMapper;
import com.aimall.server.mapper.ProductStockAggregate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductServiceImplTest {
    @Test
    void skuModeUsesEnabledSkuAggregateAndNeverStaleSpuStock() {
        PmsSkuStockMapper skuMapper = mock(PmsSkuStockMapper.class);
        ProductServiceImpl service = new ProductServiceImpl(
                mock(PmsProductMapper.class), mock(PmsProductCategoryMapper.class), skuMapper
        );
        PmsProduct product = new PmsProduct();
        product.setId(10L);
        product.setStock(999);
        product.setLockStock(0);
        when(skuMapper.aggregateAvailableStock(List.of(10L))).thenReturn(List.of(aggregate(10L, 2L, 7)));

        assertEquals(7, service.availableStock(product));
    }

    @Test
    void allDisabledSkusProduceZeroAvailableStock() {
        PmsSkuStockMapper skuMapper = mock(PmsSkuStockMapper.class);
        ProductServiceImpl service = new ProductServiceImpl(
                mock(PmsProductMapper.class), mock(PmsProductCategoryMapper.class), skuMapper
        );
        PmsProduct product = new PmsProduct();
        product.setId(10L);
        product.setStock(999);
        when(skuMapper.aggregateAvailableStock(List.of(10L))).thenReturn(List.of(aggregate(10L, 1L, 0)));

        assertEquals(0, service.availableStock(product));
    }

    @Test
    void batchAvailableStockUsesSingleAggregateQuery() {
        PmsSkuStockMapper skuMapper = mock(PmsSkuStockMapper.class);
        ProductServiceImpl service = new ProductServiceImpl(
                mock(PmsProductMapper.class), mock(PmsProductCategoryMapper.class), skuMapper
        );
        PmsProduct skuProduct = product(10L, 999, 0);
        PmsProduct standalone = product(11L, 12, 2);
        when(skuMapper.aggregateAvailableStock(List.of(10L, 11L)))
                .thenReturn(List.of(aggregate(10L, 2L, 7)));

        var stocks = service.availableStocks(List.of(skuProduct, standalone));

        assertEquals(7, stocks.get(10L));
        assertEquals(10, stocks.get(11L));
        verify(skuMapper).aggregateAvailableStock(List.of(10L, 11L));
    }

    @Test
    void productPageCombinesCategoryKeywordStockFilterAndDatabasePriceSort() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "product-test"),
                PmsProduct.class
        );
        PmsProductMapper productMapper = mock(PmsProductMapper.class);
        ProductServiceImpl service = new ProductServiceImpl(
                productMapper, mock(PmsProductCategoryMapper.class), mock(PmsSkuStockMapper.class)
        );
        when(productMapper.selectCount(any())).thenReturn(0L);
        when(productMapper.selectList(any())).thenReturn(List.of());

        service.pageProducts(8L, " phone ", "PRICE_ASC", true, 2, 20);

        var countCaptor = org.mockito.ArgumentCaptor.forClass(
                com.baomidou.mybatisplus.core.conditions.Wrapper.class
        );
        var listCaptor = org.mockito.ArgumentCaptor.forClass(
                com.baomidou.mybatisplus.core.conditions.Wrapper.class
        );
        verify(productMapper).selectCount(countCaptor.capture());
        verify(productMapper).selectList(listCaptor.capture());
        String countSql = countCaptor.getValue().getCustomSqlSegment();
        String listSql = listCaptor.getValue().getCustomSqlSegment();
        assertTrue(countSql.contains("category_id"));
        assertTrue(countSql.contains("pms_sku_stock"));
        assertTrue(countSql.contains("stock - sku_available.lock_stock > 0"));
        assertTrue(listSql.contains("COALESCE(promotion_price_v2, price_v2, promotion_price, price) ASC"));
        assertTrue(listSql.contains("LIMIT 20, 20"));
    }

    @Test
    void productPageRejectsUnknownSortInsteadOfAppendingUntrustedSql() {
        ProductServiceImpl service = new ProductServiceImpl(
                mock(PmsProductMapper.class), mock(PmsProductCategoryMapper.class), mock(PmsSkuStockMapper.class)
        );
        assertThrows(IllegalArgumentException.class,
                () -> service.pageProducts(null, null, "price desc; drop table", false, 1, 20));
    }

    private PmsProduct product(Long id, int stock, int locked) {
        PmsProduct product = new PmsProduct();
        product.setId(id);
        product.setStock(stock);
        product.setLockStock(locked);
        return product;
    }

    private ProductStockAggregate aggregate(Long productId, Long skuCount, Integer availableStock) {
        ProductStockAggregate aggregate = new ProductStockAggregate();
        aggregate.setProductId(productId);
        aggregate.setSkuCount(skuCount);
        aggregate.setAvailableStock(availableStock);
        return aggregate;
    }
}
