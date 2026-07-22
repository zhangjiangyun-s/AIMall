package com.aimall.server.service.impl;

import com.aimall.server.entity.PmsProduct;
import com.aimall.server.admin.dto.SkuUpdateRequest;
import com.aimall.server.entity.PmsProductImage;
import com.aimall.server.entity.PmsSkuStock;
import com.aimall.server.mapper.PmsProductImageMapper;
import com.aimall.server.mapper.PmsProductMapper;
import com.aimall.server.mapper.PmsSkuStockMapper;
import com.aimall.server.mapper.PmsCategoryAttributeTemplateMapper;
import com.aimall.server.entity.PmsCategoryAttributeTemplate;
import com.aimall.server.service.AdminProductCatalogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aimall.server.money.MoneyPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class AdminProductCatalogServiceImpl implements AdminProductCatalogService {

    private final PmsProductMapper productMapper;
    private final PmsSkuStockMapper skuMapper;
    private final PmsProductImageMapper imageMapper;
    private final ObjectMapper objectMapper;
    private final ProductPriceHistoryService priceHistoryService;
    private final PmsCategoryAttributeTemplateMapper attributeTemplateMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private InventoryAdjustmentLedgerService inventoryAdjustmentLedgerService;

    public AdminProductCatalogServiceImpl(
            PmsProductMapper productMapper,
            PmsSkuStockMapper skuMapper,
            PmsProductImageMapper imageMapper,
            ObjectMapper objectMapper
    ) {
        this(productMapper, skuMapper, imageMapper, objectMapper, null, null);
    }

    public AdminProductCatalogServiceImpl(
            PmsProductMapper productMapper,
            PmsSkuStockMapper skuMapper,
            PmsProductImageMapper imageMapper,
            ObjectMapper objectMapper,
            ProductPriceHistoryService priceHistoryService
    ) {
        this(productMapper, skuMapper, imageMapper, objectMapper, priceHistoryService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AdminProductCatalogServiceImpl(
            PmsProductMapper productMapper,
            PmsSkuStockMapper skuMapper,
            PmsProductImageMapper imageMapper,
            ObjectMapper objectMapper,
            ProductPriceHistoryService priceHistoryService,
            PmsCategoryAttributeTemplateMapper attributeTemplateMapper
    ) {
        this.productMapper = productMapper;
        this.skuMapper = skuMapper;
        this.imageMapper = imageMapper;
        this.objectMapper = objectMapper;
        this.priceHistoryService = priceHistoryService;
        this.attributeTemplateMapper = attributeTemplateMapper;
    }

    @Override
    public List<PmsSkuStock> listSkus(Long productId) {
        requireProduct(productId);
        return skuMapper.selectList(
                new LambdaQueryWrapper<PmsSkuStock>().eq(PmsSkuStock::getProductId, productId).orderByAsc(PmsSkuStock::getId)
        );
    }

    @Override
    public PmsSkuStock createSku(Long productId, PmsSkuStock sku) {
        PmsProduct product = requireProduct(productId);
        sku.setId(null);
        sku.setProductId(productId);
        sku.setLockStock(0);
        sku.setSale(0);
        applyDefaults(sku);
        validateSku(sku, product);
        skuMapper.insert(sku);
        return sku;
    }

    @Override
    @Transactional
    public PmsSkuStock updateSku(Long productId, Long skuId, SkuUpdateRequest patch) {
        PmsProduct product = requireProduct(productId);
        PmsSkuStock sku = skuMapper.selectById(skuId);
        if (sku == null || !productId.equals(sku.getProductId())) {
            throw new RuntimeException("SKU 不存在");
        }
        if (patch.isStockPresent()) {
            throw new IllegalArgumentException("SKU 库存必须通过独立库存调整接口修改");
        }
        BigDecimal oldPrice = sku.getPrice();
        BigDecimal oldPromotionPrice = sku.getPromotionPrice();
        if (patch.isSkuCodePresent()) sku.setSkuCode(patch.getSkuCode());
        if (patch.isPricePresent()) sku.setPrice(patch.getPrice());
        if (patch.isPromotionPricePresent()) sku.setPromotionPrice(patch.getPromotionPrice());
        if (patch.isLowStockPresent()) sku.setLowStock(patch.getLowStock());
        if (patch.isPicPresent()) sku.setPic(patch.getPic());
        if (patch.isSpDataPresent()) sku.setSpData(patch.getSpData());
        if (patch.isStatusPresent()) sku.setStatus(patch.getStatus());
        validateSku(sku, product);
        PmsSkuStockMapper.AdminUpdate update = new PmsSkuStockMapper.AdminUpdate(
                skuId, productId, sku.getSkuCode(), sku.getPrice(), sku.getPromotionPrice(),
                sku.getLowStock(), sku.getPic(), sku.getSpData(), sku.getStatus(),
                patch.isSkuCodePresent(),
                patch.isPricePresent(),
                patch.isPromotionPricePresent(),
                patch.isLowStockPresent(),
                patch.isPicPresent(),
                patch.isSpDataPresent(),
                patch.isStatusPresent()
        );
        if (!update.hasChanges()) {
            throw new IllegalArgumentException("没有可更新的 SKU 字段");
        }
        if (skuMapper.updateAdminFields(update) != 1) {
            throw new RuntimeException("SKU 库存锁定状态已变化，请刷新后重试");
        }
        if (priceHistoryService != null) {
            if (patch.isPricePresent()) {
                priceHistoryService.recordSku(productId, skuId, "BASE", oldPrice, sku.getPrice(), "ADMIN_UPDATE");
            }
            if (patch.isPromotionPricePresent()) {
                priceHistoryService.recordSku(productId, skuId, "PROMOTION", oldPromotionPrice,
                        sku.getPromotionPrice(), "ADMIN_UPDATE");
            }
        }
        return skuMapper.selectById(skuId);
    }

    @Override
    @Transactional
    public PmsSkuStock adjustSkuStock(Long productId, Long skuId, int delta) {
        if (delta == 0) {
            throw new IllegalArgumentException("库存调整数量不能为 0");
        }
        PmsSkuStock sku = skuMapper.selectById(skuId);
        if (sku == null || !productId.equals(sku.getProductId())) {
            throw new RuntimeException("SKU 不存在");
        }
        if (skuMapper.adjustStock(skuId, productId, delta) != 1) {
            throw new RuntimeException("库存调整后不能小于锁定库存或 0，请刷新后重试");
        }
        if (inventoryAdjustmentLedgerService != null) {
            inventoryAdjustmentLedgerService.record(productId, skuId, delta);
        }
        return skuMapper.selectById(skuId);
    }

    @Override
    public void disableSku(Long productId, Long skuId) {
        PmsSkuStock sku = skuMapper.selectById(skuId);
        if (sku == null || !productId.equals(sku.getProductId())) {
            throw new RuntimeException("SKU 不存在");
        }
        if (skuMapper.disableForAdmin(skuId, productId) != 1) {
            throw new RuntimeException("SKU 仍有锁定库存或状态已变化，不能停用");
        }
    }

    @Override
    public List<PmsProductImage> listImages(Long productId) {
        requireProduct(productId);
        return imageMapper.selectList(
                new LambdaQueryWrapper<PmsProductImage>()
                        .eq(PmsProductImage::getProductId, productId)
                        .orderByDesc(PmsProductImage::getIsPrimary)
                        .orderByAsc(PmsProductImage::getSort)
        );
    }

    @Override
    @Transactional
    public List<PmsProductImage> replaceImages(Long productId, List<String> imageUrls) {
        PmsProduct product = requireProduct(productId);
        List<String> normalized = imageUrls == null ? List.of() : imageUrls.stream()
                .map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(20)
                .toList();
        imageMapper.delete(new LambdaQueryWrapper<PmsProductImage>().eq(PmsProductImage::getProductId, productId));
        for (int index = 0; index < normalized.size(); index++) {
            PmsProductImage image = new PmsProductImage();
            image.setProductId(productId);
            image.setImageUrl(normalized.get(index));
            image.setSort(index);
            image.setIsPrimary(index == 0 ? 1 : 0);
            image.setCreateTime(LocalDateTime.now());
            imageMapper.insert(image);
        }
        product.setPic(normalized.isEmpty() ? null : normalized.get(0));
        productMapper.updateById(product);
        return listImages(productId);
    }

    private PmsProduct requireProduct(Long productId) {
        PmsProduct product = productMapper.selectById(productId);
        if (product == null || product.getDeleteStatus() == null || product.getDeleteStatus() != 0) {
            throw new RuntimeException("商品不存在");
        }
        return product;
    }

    private void applyDefaults(PmsSkuStock sku) {
        if (sku.getStock() == null) sku.setStock(0);
        if (sku.getLowStock() == null) sku.setLowStock(0);
        if (sku.getLockStock() == null) sku.setLockStock(0);
        if (sku.getSale() == null) sku.setSale(0);
        if (sku.getStatus() == null) sku.setStatus(1);
    }

    private void validateSku(PmsSkuStock sku, PmsProduct product) {
        if (sku.getSkuCode() == null || sku.getSkuCode().isBlank()) throw new IllegalArgumentException("SKU 编码不能为空");
        validateMoney(sku.getPrice(), "SKU 价格");
        if (sku.getPromotionPrice() != null) {
            validateMoney(sku.getPromotionPrice(), "SKU 促销价");
            if (sku.getPromotionPrice().compareTo(sku.getPrice()) > 0) throw new IllegalArgumentException("SKU 促销价不能高于售价");
        }
        if (sku.getStock() == null || sku.getStock() < 0) throw new IllegalArgumentException("SKU 库存不能小于 0");
        int locked = sku.getLockStock() == null ? 0 : sku.getLockStock();
        if (sku.getStock() < locked) throw new IllegalArgumentException("SKU 库存不能低于已锁定库存");
        if (sku.getLowStock() == null || sku.getLowStock() < 0 || sku.getLowStock() > sku.getStock()) {
            throw new IllegalArgumentException("SKU 低库存阈值必须在 0 到库存之间");
        }
        if (sku.getStatus() == null || (sku.getStatus() != 0 && sku.getStatus() != 1)) throw new IllegalArgumentException("SKU 状态只能是 0 或 1");
        if (sku.getSpData() != null && !sku.getSpData().isBlank()) {
            try {
                if (!objectMapper.readTree(sku.getSpData()).isArray()) throw new IllegalArgumentException("SKU 规格必须是 JSON 数组");
            } catch (Exception exception) {
                throw new IllegalArgumentException("SKU 规格必须是合法 JSON 数组");
            }
        }
        validateCategoryAttributes(product, sku.getSpData());
    }

    private void validateCategoryAttributes(PmsProduct product, String spData) {
        if (attributeTemplateMapper == null || product.getCategoryId() == null) return;
        PmsCategoryAttributeTemplate template = attributeTemplateMapper.selectOne(
                new LambdaQueryWrapper<PmsCategoryAttributeTemplate>()
                        .eq(PmsCategoryAttributeTemplate::getCategoryId, product.getCategoryId())
                        .eq(PmsCategoryAttributeTemplate::getStatus, 1)
                        .orderByDesc(PmsCategoryAttributeTemplate::getId).last("LIMIT 1")
        );
        if (template == null) return;
        try {
            var values = new java.util.HashMap<String, String>();
            var actual = objectMapper.readTree(spData == null || spData.isBlank() ? "[]" : spData);
            for (var item : actual) {
                String name = item.hasNonNull("name") ? item.get("name").asText()
                        : item.hasNonNull("key") ? item.get("key").asText() : "";
                String value = item.hasNonNull("value") ? item.get("value").asText() : "";
                if (!name.isBlank()) values.put(name, value);
            }
            for (var field : objectMapper.readTree(template.getSchemaJson())) {
                String name = field.get("name").asText();
                String value = values.get(name);
                if (field.path("required").asBoolean(false) && (value == null || value.isBlank()))
                    throw new IllegalArgumentException("SKU 缺少必填规格：" + name);
                if (value != null && field.has("values") && field.get("values").isArray()) {
                    boolean allowed = false;
                    for (var candidate : field.get("values")) if (value.equals(candidate.asText())) allowed = true;
                    if (!allowed) throw new IllegalArgumentException("SKU 规格值不允许：" + name + "=" + value);
                }
            }
        } catch (IllegalArgumentException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalArgumentException("SKU 规格与类目属性模板不匹配"); }
    }

    private void validateMoney(BigDecimal amount, String field) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0
                || amount.scale() > MoneyPolicy.STORAGE_SCALE) {
            throw new IllegalArgumentException(field + "必须是非负且最多两位小数");
        }
    }
}
