package com.aimall.server.service.impl;

import com.aimall.server.entity.PmsProduct;
import com.aimall.server.admin.dto.ProductUpdateRequest;
import com.aimall.server.entity.PmsProductCategory;
import com.aimall.server.entity.KnowledgeDoc;
import com.aimall.server.entity.KnowledgeIndexTask;
import com.aimall.server.mapper.KnowledgeDocMapper;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.mapper.PmsProductCategoryMapper;
import com.aimall.server.mapper.PmsProductMapper;
import com.aimall.server.service.KnowledgeDocAuditLogService;
import com.aimall.server.service.KnowledgeIndexTaskService;
import com.aimall.server.service.KnowledgePublicationService;
import com.aimall.server.service.KnowledgeTaskDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminServiceImplTest {

    private PmsProductMapper productMapper;
    private PmsProductCategoryMapper categoryMapper;
    private KnowledgeDocMapper docMapper;
    private KnowledgeIndexTaskService indexTaskService;
    private KnowledgeTaskDispatcher taskDispatcher;
    private AdminServiceImpl adminService;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "AdminServiceImplTest"),
                PmsProduct.class
        );
        productMapper = mock(PmsProductMapper.class);
        categoryMapper = mock(PmsProductCategoryMapper.class);
        docMapper = mock(KnowledgeDocMapper.class);
        indexTaskService = mock(KnowledgeIndexTaskService.class);
        taskDispatcher = mock(KnowledgeTaskDispatcher.class);
        adminService = new AdminServiceImpl(
                productMapper,
                docMapper,
                mock(OmsOrderMapper.class),
                indexTaskService,
                mock(KnowledgeDocAuditLogService.class),
                categoryMapper,
                mock(KnowledgePublicationService.class),
                taskDispatcher
        );
    }

    @Test
    void createProductRejectsMissingRequiredFields() {
        PmsProduct product = new PmsProduct();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> adminService.createProduct(product)
        );

        assertEquals("商品名称不能为空", exception.getMessage());
    }

    @Test
    void createProductRejectsNegativeStock() {
        PmsProduct product = validProduct();
        product.setStock(-1);
        PmsProductCategory category = category();
        when(categoryMapper.selectById(category.getId())).thenReturn(category);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> adminService.createProduct(product)
        );

        assertEquals("商品库存必须大于或等于 0", exception.getMessage());
    }

    @Test
    void createProductUsesValidatedCategoryAndPersistsProduct() {
        PmsProduct product = validProduct();
        PmsProductCategory category = category();
        when(categoryMapper.selectById(category.getId())).thenReturn(category);
        when(productMapper.selectCount(any())).thenReturn(0L);

        PmsProduct created = adminService.createProduct(product);

        assertEquals("笔记本电脑", created.getProductCategoryName());
        verify(productMapper).insert(product);
    }

    @Test
    void updateProductDoesNotWriteBackInventorySnapshot() {
        PmsProduct existing = validProduct();
        existing.setId(10L);
        existing.setDeleteStatus(0);
        existing.setLockStock(3);
        existing.setSale(7);
        when(productMapper.selectById(10L)).thenReturn(existing);
        when(productMapper.selectCount(any())).thenReturn(0L);
        when(categoryMapper.selectById(3L)).thenReturn(category());
        when(productMapper.update(isNull(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class)))
                .thenReturn(1);

        ProductUpdateRequest patch = new ProductUpdateRequest();
        patch.setName("改名后的商品");
        adminService.updateProduct(10L, patch);

        verify(productMapper, never()).updateById(any(PmsProduct.class));
    }

    @Test
    void explicitNullClearsNullableProductPrices() {
        PmsProduct existing = validProduct();
        existing.setId(10L);
        existing.setDeleteStatus(0);
        existing.setPromotionPrice(new BigDecimal("1999.00"));
        existing.setOriginalPrice(new BigDecimal("3999.00"));
        when(productMapper.selectById(10L)).thenReturn(existing);
        when(productMapper.selectCount(any())).thenReturn(0L);
        when(categoryMapper.selectById(3L)).thenReturn(category());
        when(productMapper.update(isNull(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class)))
                .thenReturn(1);
        ProductUpdateRequest patch = new ProductUpdateRequest();
        patch.setPromotionPrice(null);
        patch.setOriginalPrice(null);

        adminService.updateProduct(10L, patch);

        assertEquals(null, existing.getPromotionPrice());
        assertEquals(null, existing.getOriginalPrice());
    }

    @Test
    void knowledgeRebuildCreatesVersionedUploadTasksOnly() {
        KnowledgeDoc versioned = new KnowledgeDoc();
        versioned.setId(21L);
        versioned.setCurrentVersionId(31L);
        versioned.setStatus("ACTIVE");
        KnowledgeDoc legacy = new KnowledgeDoc();
        legacy.setId(22L);
        legacy.setStatus("ENABLED");
        when(docMapper.selectList(any())).thenReturn(List.of(versioned, legacy));
        KnowledgeIndexTask task = new KnowledgeIndexTask();
        task.setTaskId("KT-REBUILD-1");
        when(indexTaskService.createUploadDocTask(21L, 31L, "MANUAL_REBUILD")).thenReturn(task);

        adminService.rebuildDocs();

        verify(indexTaskService).createUploadDocTask(21L, 31L, "MANUAL_REBUILD");
        verify(taskDispatcher).dispatchAfterCommit("KT-REBUILD-1");
        verify(indexTaskService, never()).createRebuildDocTask(any(), any());
    }

    @Test
    void adminProductPaginationLoadsOnlyRequestedPage() {
        PmsProduct product = validProduct();
        product.setId(101L);
        when(productMapper.selectCount(any())).thenReturn(25L);
        when(productMapper.selectList(any())).thenReturn(List.of(product));

        var result = adminService.pageProducts("测试", 2, 10);

        assertEquals(25L, result.getTotal());
        assertEquals(2, result.getPage());
        assertEquals(10, result.getSize());
        assertEquals(List.of(product), result.getList());
        verify(productMapper).selectList(any());
    }

    private PmsProduct validProduct() {
        PmsProduct product = new PmsProduct();
        product.setName("测试商品");
        product.setProductSn("TEST-001");
        product.setCategoryId(3L);
        product.setPrice(new BigDecimal("2999.00"));
        product.setStock(10);
        product.setLowStock(0);
        product.setPublishStatus(1);
        product.setNewStatus(0);
        product.setRecommandStatus(0);
        return product;
    }

    @Test
    void publishStateUsesDedicatedDatabaseTransition() {
        PmsProduct draft = validProduct();
        draft.setId(101L);
        draft.setDeleteStatus(0);
        draft.setPublishStatus(0);
        PmsProduct published = validProduct();
        published.setId(101L);
        published.setDeleteStatus(0);
        published.setPublishStatus(1);
        when(productMapper.selectById(101L)).thenReturn(draft, published);
        when(productMapper.transitionPublishStatus(101L, 0, 1)).thenReturn(1);

        PmsProduct result = adminService.changeProductPublishStatus(101L, true);

        assertEquals(1, result.getPublishStatus());
        verify(productMapper).transitionPublishStatus(101L, 0, 1);
    }

    private PmsProductCategory category() {
        PmsProductCategory category = new PmsProductCategory();
        category.setId(3L);
        category.setName("笔记本电脑");
        return category;
    }
}
