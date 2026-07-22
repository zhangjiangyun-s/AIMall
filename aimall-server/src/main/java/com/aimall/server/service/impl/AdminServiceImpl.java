package com.aimall.server.service.impl;

import com.aimall.server.entity.KnowledgeDoc;
import com.aimall.server.common.PageResult;
import com.aimall.server.entity.OmsOrder;
import com.aimall.server.entity.PmsProduct;
import com.aimall.server.admin.dto.ProductUpdateRequest;
import com.aimall.server.entity.PmsProductCategory;
import com.aimall.server.mapper.KnowledgeDocMapper;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.mapper.PmsProductMapper;
import com.aimall.server.mapper.PmsProductCategoryMapper;
import com.aimall.server.money.MoneyPolicy;
import com.aimall.server.service.AdminService;
import com.aimall.server.service.KnowledgeDocAuditLogService;
import com.aimall.server.service.KnowledgeIndexTaskService;
import com.aimall.server.service.KnowledgePublicationService;
import com.aimall.server.service.KnowledgeTaskDispatcher;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminServiceImpl implements AdminService {

    private final PmsProductMapper productMapper;
    private final KnowledgeDocMapper docMapper;
    private final OmsOrderMapper orderMapper;
    private final KnowledgeIndexTaskService indexTaskService;
    private final KnowledgeDocAuditLogService auditLogService;
    private final PmsProductCategoryMapper productCategoryMapper;
    private final KnowledgePublicationService publicationService;
    private final KnowledgeTaskDispatcher taskDispatcher;
    private final ProductPriceHistoryService priceHistoryService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private InventoryAdjustmentLedgerService inventoryAdjustmentLedgerService;

    public AdminServiceImpl(
            PmsProductMapper productMapper,
            KnowledgeDocMapper docMapper,
            OmsOrderMapper orderMapper,
            KnowledgeIndexTaskService indexTaskService,
            KnowledgeDocAuditLogService auditLogService,
            PmsProductCategoryMapper productCategoryMapper,
            KnowledgePublicationService publicationService,
            KnowledgeTaskDispatcher taskDispatcher
    ) {
        this(productMapper, docMapper, orderMapper, indexTaskService, auditLogService,
                productCategoryMapper, publicationService, taskDispatcher, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AdminServiceImpl(
            PmsProductMapper productMapper,
            KnowledgeDocMapper docMapper,
            OmsOrderMapper orderMapper,
            KnowledgeIndexTaskService indexTaskService,
            KnowledgeDocAuditLogService auditLogService,
            PmsProductCategoryMapper productCategoryMapper,
            KnowledgePublicationService publicationService,
            KnowledgeTaskDispatcher taskDispatcher,
            ProductPriceHistoryService priceHistoryService
    ) {
        this.productMapper = productMapper;
        this.docMapper = docMapper;
        this.orderMapper = orderMapper;
        this.indexTaskService = indexTaskService;
        this.auditLogService = auditLogService;
        this.productCategoryMapper = productCategoryMapper;
        this.publicationService = publicationService;
        this.taskDispatcher = taskDispatcher;
        this.priceHistoryService = priceHistoryService;
    }

    @Override
    public List<PmsProduct> listProducts() {
        return productMapper.selectList(
                new LambdaQueryWrapper<PmsProduct>()
                        .eq(PmsProduct::getDeleteStatus, 0)
                        .orderByDesc(PmsProduct::getSort)
                        .orderByAsc(PmsProduct::getId)
        );
    }

    @Override
    public PageResult<PmsProduct> pageProducts(String keyword, Integer page, Integer size) {
        int safePage = page == null || page <= 0 ? 1 : page;
        int safeSize = size == null || size <= 0 ? 20 : Math.min(size, 100);
        LambdaQueryWrapper<PmsProduct> countQuery = adminProductQuery(keyword);
        long total = productMapper.selectCount(countQuery);
        LambdaQueryWrapper<PmsProduct> dataQuery = adminProductQuery(keyword)
                .orderByDesc(PmsProduct::getSort)
                .orderByAsc(PmsProduct::getId)
                .last("LIMIT " + ((long) (safePage - 1) * safeSize) + ", " + safeSize);
        return PageResult.of(productMapper.selectList(dataQuery), total, safePage, safeSize);
    }

    @Override
    public PmsProduct createProduct(PmsProduct product) {
        if (product.getDeleteStatus() == null) product.setDeleteStatus(0);
        if (product.getPublishStatus() == null) product.setPublishStatus(1);
        if (product.getNewStatus() == null) product.setNewStatus(0);
        if (product.getRecommandStatus() == null) product.setRecommandStatus(0);
        if (product.getVerifyStatus() == null) product.setVerifyStatus(1);
        if (product.getSort() == null) product.setSort(0);
        if (product.getSale() == null) product.setSale(0);
        if (product.getStock() == null) product.setStock(0);
        if (product.getLowStock() == null) product.setLowStock(0);
        if (product.getUnit() == null) product.setUnit("件");
        validateProduct(product);
        productMapper.insert(product);
        return product;
    }

    @Override
    @Transactional
    public PmsProduct updateProduct(Long productId, ProductUpdateRequest product) {
        PmsProduct existing = productMapper.selectById(productId);
        if (existing == null) {
            throw new RuntimeException("商品不存在");
        }
        if (product.isStockPresent()) {
            throw new IllegalArgumentException("商品库存必须通过独立库存调整接口修改");
        }
        if (product.isPublishStatusPresent() || product.isStatusPresent()) {
            throw new IllegalArgumentException("商品上下架必须使用独立状态接口");
        }
        if (!product.hasChanges()) {
            throw new IllegalArgumentException("没有可更新的商品字段");
        }
        BigDecimal oldPrice = existing.getPrice();
        BigDecimal oldPromotionPrice = existing.getPromotionPrice();
        BigDecimal oldOriginalPrice = existing.getOriginalPrice();
        if (product.isNamePresent()) existing.setName(product.getName());
        if (product.isCategoryIdPresent()) existing.setCategoryId(product.getCategoryId());
        if (product.isBrandIdPresent()) existing.setBrandId(product.getBrandId());
        if (product.isBrandNamePresent()) existing.setBrandName(product.getBrandName());
        if (product.isProductCategoryNamePresent()) existing.setProductCategoryName(product.getProductCategoryName());
        if (product.isSubTitlePresent()) existing.setSubTitle(product.getSubTitle());
        if (product.isKeywordsPresent()) existing.setKeywords(product.getKeywords());
        if (product.isDescriptionPresent()) existing.setDescription(product.getDescription());
        if (product.isDetailDescPresent()) existing.setDetailDesc(product.getDetailDesc());
        if (product.isPicPresent()) existing.setPic(product.getPic());
        if (product.isProductSnPresent()) existing.setProductSn(product.getProductSn());
        if (product.isPricePresent()) existing.setPrice(product.getPrice());
        if (product.isPromotionPricePresent()) existing.setPromotionPrice(product.getPromotionPrice());
        if (product.isOriginalPricePresent()) existing.setOriginalPrice(product.getOriginalPrice());
        if (product.isLowStockPresent()) existing.setLowStock(product.getLowStock());
        if (product.isNewStatusPresent()) existing.setNewStatus(product.getNewStatus());
        if (product.isRecommandStatusPresent()) existing.setRecommandStatus(product.getRecommandStatus());
        if (product.isSortPresent()) existing.setSort(product.getSort());
        validateProduct(existing);
        LambdaUpdateWrapper<PmsProduct> update = new LambdaUpdateWrapper<PmsProduct>()
                .eq(PmsProduct::getId, productId)
                .eq(PmsProduct::getDeleteStatus, 0);
        if (product.isNamePresent()) update.set(PmsProduct::getName, existing.getName());
        if (product.isCategoryIdPresent()) {
            update.set(PmsProduct::getCategoryId, existing.getCategoryId());
            update.set(PmsProduct::getProductCategoryName, existing.getProductCategoryName());
        }
        if (product.isBrandIdPresent()) update.set(PmsProduct::getBrandId, existing.getBrandId());
        if (product.isBrandNamePresent()) update.set(PmsProduct::getBrandName, existing.getBrandName());
        if (product.isProductCategoryNamePresent()) update.set(PmsProduct::getProductCategoryName, existing.getProductCategoryName());
        if (product.isSubTitlePresent()) update.set(PmsProduct::getSubTitle, existing.getSubTitle());
        if (product.isKeywordsPresent()) update.set(PmsProduct::getKeywords, existing.getKeywords());
        if (product.isDescriptionPresent()) update.set(PmsProduct::getDescription, existing.getDescription());
        if (product.isDetailDescPresent()) update.set(PmsProduct::getDetailDesc, existing.getDetailDesc());
        if (product.isPicPresent()) update.set(PmsProduct::getPic, existing.getPic());
        if (product.isProductSnPresent()) update.set(PmsProduct::getProductSn, existing.getProductSn());
        if (product.isPricePresent()) {
            update.set(PmsProduct::getPrice, existing.getPrice());
            update.set(PmsProduct::getLegacyPrice, MoneyPolicy.channel(existing.getPrice(), 2));
        }
        if (product.isPromotionPricePresent()) {
            update.set(PmsProduct::getPromotionPrice, existing.getPromotionPrice());
            update.set(PmsProduct::getLegacyPromotionPrice, existing.getPromotionPrice() == null
                    ? null : MoneyPolicy.channel(existing.getPromotionPrice(), 2));
        }
        if (product.isOriginalPricePresent()) {
            update.set(PmsProduct::getOriginalPrice, existing.getOriginalPrice());
            update.set(PmsProduct::getLegacyOriginalPrice, existing.getOriginalPrice() == null
                    ? null : MoneyPolicy.channel(existing.getOriginalPrice(), 2));
        }
        if (product.isLowStockPresent()) update.set(PmsProduct::getLowStock, existing.getLowStock());
        if (product.isNewStatusPresent()) update.set(PmsProduct::getNewStatus, existing.getNewStatus());
        if (product.isRecommandStatusPresent()) update.set(PmsProduct::getRecommandStatus, existing.getRecommandStatus());
        if (product.isSortPresent()) update.set(PmsProduct::getSort, existing.getSort());
        if (productMapper.update(null, update) != 1) {
            throw new RuntimeException("商品状态已变化，请刷新后重试");
        }
        if (priceHistoryService != null) {
            if (product.isPricePresent()) {
                priceHistoryService.recordProduct(productId, "BASE", oldPrice, existing.getPrice(), "ADMIN_UPDATE");
            }
            if (product.isPromotionPricePresent()) {
                priceHistoryService.recordProduct(productId, "PROMOTION", oldPromotionPrice,
                        existing.getPromotionPrice(), "ADMIN_UPDATE");
            }
            if (product.isOriginalPricePresent()) {
                priceHistoryService.recordProduct(productId, "ORIGINAL", oldOriginalPrice,
                        existing.getOriginalPrice(), "ADMIN_UPDATE");
            }
        }
        return productMapper.selectById(productId);
    }

    private int parseProductStatus(String status) {
        if ("上架".equals(status)) return 1;
        if ("下架".equals(status)) return 0;
        try {
            int value = Integer.parseInt(status);
            if (value == 0 || value == 1) return value;
        } catch (Exception ignored) {
        }
        throw new IllegalArgumentException("商品状态只能是上架或下架");
    }

    @Override
    public PmsProduct changeProductPublishStatus(Long productId, boolean published) {
        PmsProduct product = productMapper.selectById(productId);
        if (product == null || product.getDeleteStatus() == null || product.getDeleteStatus() != 0) {
            throw new RuntimeException("商品不存在");
        }
        int target = published ? 1 : 0;
        int current = product.getPublishStatus() == null ? 0 : product.getPublishStatus();
        if (current == target) return product;
        if (productMapper.transitionPublishStatus(productId, current, target) != 1) {
            throw new RuntimeException(published
                    ? "商品未审核、资料不完整或没有启用 SKU，不能上架"
                    : "商品状态已变化，下架失败");
        }
        return productMapper.selectById(productId);
    }

    private LambdaQueryWrapper<PmsProduct> adminProductQuery(String keyword) {
        LambdaQueryWrapper<PmsProduct> query = new LambdaQueryWrapper<PmsProduct>()
                .eq(PmsProduct::getDeleteStatus, 0);
        if (keyword != null && !keyword.isBlank()) {
            String normalized = keyword.trim();
            query.and(item -> item
                    .like(PmsProduct::getName, normalized)
                    .or()
                    .like(PmsProduct::getProductSn, normalized));
        }
        return query;
    }

    @Override
    @Transactional
    public PmsProduct adjustProductStock(Long productId, int delta) {
        if (delta == 0) {
            throw new IllegalArgumentException("库存调整数量不能为 0");
        }
        if (productMapper.adjustStandaloneStock(productId, delta) != 1) {
            throw new RuntimeException("库存调整失败：商品不存在、存在 SKU，或调整后库存小于锁定库存");
        }
        if (inventoryAdjustmentLedgerService != null) {
            inventoryAdjustmentLedgerService.record(productId, null, delta);
        }
        return productMapper.selectById(productId);
    }

    @Override
    public void deleteProduct(Long id) {
        if (productMapper.softDeleteForAdmin(id) != 1) {
            throw new RuntimeException("商品不存在、已删除或仍有锁定库存，不能删除");
        }
    }

    @Override
    public List<KnowledgeDoc> listDocs() {
        return docMapper.selectList(new LambdaQueryWrapper<KnowledgeDoc>().orderByAsc(KnowledgeDoc::getId));
    }

    @Override
    public void deleteDoc(Long id) {
        publicationService.delete(id);
    }

    @Override
    public void rebuildDocs() {
        for (KnowledgeDoc doc : listDocs()) {
            if (doc.getCurrentVersionId() != null && !"DELETED".equals(doc.getStatus())) {
                var task = indexTaskService.createUploadDocTask(doc.getId(), doc.getCurrentVersionId(), "MANUAL_REBUILD");
                taskDispatcher.dispatchAfterCommit(task.getTaskId());
            }
        }
        auditLogService.record(null, null, "REBUILD", null, null, "admin rebuild all knowledge docs");
    }

    @Override
    public List<OmsOrder> listOrders() {
        return orderMapper.selectList(
                new LambdaQueryWrapper<OmsOrder>()
                        .eq(OmsOrder::getDeleteStatus, 0)
                        .orderByDesc(OmsOrder::getId)
        );
    }

    @Override
    public OmsOrder getOrderById(Long orderId) {
        OmsOrder order = requireOrder(orderId);
        if (order.getDeleteStatus() != null && order.getDeleteStatus() == 1) {
            throw new RuntimeException("订单不存在");
        }
        return order;
    }

    @Override
    public Map<String, Object> dashboard() {
        Map<String, Object> data = new HashMap<>();
        data.put("productCount", productMapper.selectCount(new LambdaQueryWrapper<PmsProduct>().eq(PmsProduct::getDeleteStatus, 0)));
        data.put("docCount", docMapper.selectCount(null));
        data.put("pendingOrderCount", orderMapper.selectCount(
                new LambdaQueryWrapper<OmsOrder>()
                        .eq(OmsOrder::getDeleteStatus, 0)
                        .eq(OmsOrder::getStatus, 0)
        ));
        return data;
    }

    private void applyKnowledgeDocDefaults(KnowledgeDoc doc) {
        LocalDateTime now = LocalDateTime.now();
        if (doc.getSourceSystem() == null) doc.setSourceSystem("admin");
        if (doc.getSourceTrustScore() == null) doc.setSourceTrustScore(BigDecimal.valueOf(0.5));
        if (doc.getVisibilityScope() == null) doc.setVisibilityScope("PUBLIC_USER");
        if (doc.getTenantId() == null) doc.setTenantId("default");
        if (doc.getCreatedAt() == null) doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
    }

    private void validateProduct(PmsProduct product) {
        if (product.getName() == null || product.getName().isBlank()) {
            throw new IllegalArgumentException("商品名称不能为空");
        }
        if (product.getProductSn() == null || product.getProductSn().isBlank()) {
            throw new IllegalArgumentException("商品货号不能为空");
        }
        if (product.getCategoryId() == null) {
            throw new IllegalArgumentException("商品分类不能为空");
        }
        PmsProductCategory category = productCategoryMapper.selectById(product.getCategoryId());
        if (category == null) {
            throw new IllegalArgumentException("商品分类不存在");
        }
        if (product.getPrice() == null || product.getPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("商品价格必须大于或等于 0");
        }
        validateMoney("商品价格", product.getPrice());
        validateOptionalMoney("促销价", product.getPromotionPrice());
        validateOptionalMoney("原价", product.getOriginalPrice());
        if (product.getPromotionPrice() != null && product.getPromotionPrice().compareTo(product.getPrice()) > 0) {
            throw new IllegalArgumentException("促销价不能高于商品价格");
        }
        if (product.getOriginalPrice() != null && product.getOriginalPrice().compareTo(product.getPrice()) < 0) {
            throw new IllegalArgumentException("原价不能低于商品价格");
        }
        if (product.getStock() == null || product.getStock() < 0) {
            throw new IllegalArgumentException("商品库存必须大于或等于 0");
        }
        if (product.getLowStock() == null || product.getLowStock() < 0 || product.getLowStock() > product.getStock()) {
            throw new IllegalArgumentException("低库存阈值必须在 0 到商品库存之间");
        }
        if (product.getPublishStatus() == null || product.getPublishStatus() < 0 || product.getPublishStatus() > 1) {
            throw new IllegalArgumentException("商品状态只能是上架或下架");
        }
        validateBinaryStatus("新品状态", product.getNewStatus());
        validateBinaryStatus("推荐状态", product.getRecommandStatus());
        Long duplicateCount = productMapper.selectCount(
                new LambdaQueryWrapper<PmsProduct>()
                        .eq(PmsProduct::getProductSn, product.getProductSn().trim())
                        .ne(product.getId() != null, PmsProduct::getId, product.getId())
        );
        if (duplicateCount > 0) {
            throw new IllegalArgumentException("商品货号已存在");
        }
        product.setName(product.getName().trim());
        product.setProductSn(product.getProductSn().trim());
        product.setProductCategoryName(category.getName());
    }

    private void validateMoney(String field, BigDecimal value) {
        if (value.scale() > MoneyPolicy.STORAGE_SCALE) {
            throw new IllegalArgumentException(field + "最多保留两位小数");
        }
    }

    private void validateOptionalMoney(String field, BigDecimal value) {
        if (value == null) {
            return;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(field + "必须大于或等于 0");
        }
        validateMoney(field, value);
    }

    private void validateBinaryStatus(String field, Integer value) {
        if (value == null || (value != 0 && value != 1)) {
            throw new IllegalArgumentException(field + "只能是 0 或 1");
        }
    }

    private KnowledgeDoc copyKnowledgeDoc(KnowledgeDoc source) {
        KnowledgeDoc target = new KnowledgeDoc();
        target.setId(source.getId());
        target.setTitle(source.getTitle());
        target.setSourceType(source.getSourceType());
        target.setContent(source.getContent());
        target.setStatus(source.getStatus());
        target.setVersion(source.getVersion());
        target.setSourceSystem(source.getSourceSystem());
        target.setSourceTrustScore(source.getSourceTrustScore());
        target.setSourceUri(source.getSourceUri());
        target.setSourceHash(source.getSourceHash());
        target.setExternalDocId(source.getExternalDocId());
        target.setVisibilityScope(source.getVisibilityScope());
        target.setTenantId(source.getTenantId());
        target.setRoleScope(source.getRoleScope());
        target.setCategoryIds(source.getCategoryIds());
        target.setActivityId(source.getActivityId());
        target.setTags(source.getTags());
        target.setEffectiveTime(source.getEffectiveTime());
        target.setExpireTime(source.getExpireTime());
        target.setCreatedBy(source.getCreatedBy());
        target.setUpdatedBy(source.getUpdatedBy());
        target.setCreatedAt(source.getCreatedAt());
        target.setUpdatedAt(source.getUpdatedAt());
        return target;
    }

    private OmsOrder requireOrder(Long orderId) {
        OmsOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }
        return order;
    }
}
