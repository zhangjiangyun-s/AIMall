package com.aimall.server.service.impl;

import com.aimall.server.common.PageResult;
import com.aimall.server.entity.PmsProduct;
import com.aimall.server.entity.PmsProductCategory;
import com.aimall.server.entity.PmsSkuStock;
import com.aimall.server.mapper.PmsProductCategoryMapper;
import com.aimall.server.mapper.PmsProductMapper;
import com.aimall.server.mapper.PmsSkuStockMapper;
import com.aimall.server.mapper.ProductStockAggregate;
import com.aimall.server.service.ProductService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProductServiceImpl implements ProductService {

    private final PmsProductMapper productMapper;
    private final PmsProductCategoryMapper categoryMapper;
    private final PmsSkuStockMapper skuStockMapper;

    public ProductServiceImpl(
            PmsProductMapper productMapper,
            PmsProductCategoryMapper categoryMapper,
            PmsSkuStockMapper skuStockMapper
    ) {
        this.productMapper = productMapper;
        this.categoryMapper = categoryMapper;
        this.skuStockMapper = skuStockMapper;
    }

    @Override
    public List<PmsProduct> listAll() {
        return productMapper.selectList(basePublishedWrapper().orderByDesc(PmsProduct::getSort).orderByAsc(PmsProduct::getId));
    }

    @Override
    public PageResult<PmsProduct> pageProducts(Long categoryId, String keyword, String sort,
                                               Boolean inStock, Integer page, Integer size) {
        int safePage = page == null || page <= 0 ? 1 : page;
        int safeSize = size == null || size <= 0 ? 20 : Math.min(size, 100);
        String safeSort = normalizeSort(sort);
        LambdaQueryWrapper<PmsProduct> countWrapper = listWrapper(categoryId, keyword, inStock);
        long total = productMapper.selectCount(countWrapper);
        LambdaQueryWrapper<PmsProduct> dataWrapper = listWrapper(categoryId, keyword, inStock);
        long offset = (long) (safePage - 1) * safeSize;
        if ("PRICE_ASC".equals(safeSort) || "PRICE_DESC".equals(safeSort)) {
            String direction = "PRICE_ASC".equals(safeSort) ? "ASC" : "DESC";
            dataWrapper.last("ORDER BY COALESCE(promotion_price_v2, price_v2, promotion_price, price) " + direction
                    + ", id ASC LIMIT " + offset + ", " + safeSize);
        } else {
            applySort(dataWrapper, safeSort, keyword);
            dataWrapper.last("LIMIT " + offset + ", " + safeSize);
        }
        return PageResult.of(productMapper.selectList(dataWrapper), total, safePage, safeSize);
    }

    @Override
    public List<PmsProduct> listByCategory(Long categoryId) {
        return productMapper.selectList(
                basePublishedWrapper()
                        .eq(PmsProduct::getCategoryId, categoryId)
                        .orderByDesc(PmsProduct::getSort)
                        .orderByAsc(PmsProduct::getId)
        );
    }

    @Override
    public List<PmsProduct> search(String keyword) {
        return search(keyword, null);
    }

    @Override
    public List<PmsProduct> search(String keyword, Integer limit) {
        LambdaQueryWrapper<PmsProduct> wrapper = basePublishedWrapper();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(q -> q.like(PmsProduct::getName, keyword)
                    .or()
                    .like(PmsProduct::getKeywords, keyword)
                    .or()
                    .like(PmsProduct::getSubTitle, keyword)
                    .or()
                    .like(PmsProduct::getDescription, keyword));
        }
        wrapper.orderByDesc(PmsProduct::getSale).orderByAsc(PmsProduct::getId);
        if (limit != null && limit > 0) {
            wrapper.last("LIMIT " + Math.min(limit, 200));
        }
        return productMapper.selectList(wrapper);
    }

    @Override
    public List<PmsProduct> listRecommendProducts(Integer limit) {
        return productMapper.selectList(
                basePublishedWrapper()
                        .eq(PmsProduct::getRecommandStatus, 1)
                        .orderByDesc(PmsProduct::getSort)
                        .orderByDesc(PmsProduct::getSale)
                        .last(limitClause(limit))
        );
    }

    @Override
    public List<PmsProduct> listNewProducts(Integer limit) {
        return productMapper.selectList(
                basePublishedWrapper()
                        .eq(PmsProduct::getNewStatus, 1)
                        .orderByDesc(PmsProduct::getCreateTime)
                        .last(limitClause(limit))
        );
    }

    @Override
    public List<PmsProduct> listHotProducts(Integer limit) {
        return productMapper.selectList(
                basePublishedWrapper()
                        .orderByDesc(PmsProduct::getSale)
                        .orderByDesc(PmsProduct::getRecommandStatus)
                        .last(limitClause(limit))
        );
    }

    @Override
    public List<PmsProductCategory> listCategories(Long parentId) {
        return categoryMapper.selectList(
                new LambdaQueryWrapper<PmsProductCategory>()
                        .eq(PmsProductCategory::getShowStatus, 1)
                        .eq(PmsProductCategory::getParentId, parentId == null ? 0L : parentId)
                        .orderByDesc(PmsProductCategory::getSort)
                        .orderByAsc(PmsProductCategory::getId)
        );
    }

    @Override
    public List<PmsProductCategory> listHomeCategories() {
        List<PmsProductCategory> roots = listCategories(0L);
        if (roots.size() != 1) {
            return roots;
        }
        List<PmsProductCategory> children = listCategories(roots.get(0).getId());
        return children.isEmpty() ? roots : children;
    }

    @Override
    public List<PmsSkuStock> listSkuStocks(Long productId) {
        return skuStockMapper.selectList(
                new LambdaQueryWrapper<PmsSkuStock>()
                        .eq(PmsSkuStock::getProductId, productId)
                        .eq(PmsSkuStock::getStatus, 1)
                        .orderByAsc(PmsSkuStock::getId)
        );
    }

    @Override
    public boolean isSkuMode(Long productId) {
        return skuStockMapper.countAllByProductId(productId) > 0;
    }

    @Override
    public int availableStock(PmsProduct product) {
        if (product == null) return 0;
        return availableStocks(List.of(product)).getOrDefault(product.getId(), 0);
    }

    @Override
    public Map<Long, Integer> availableStocks(List<PmsProduct> products) {
        if (products == null || products.isEmpty()) {
            return Map.of();
        }
        List<PmsProduct> validProducts = products.stream()
                .filter(product -> product != null && product.getId() != null)
                .toList();
        if (validProducts.isEmpty()) {
            return Map.of();
        }
        Map<Long, ProductStockAggregate> aggregates = skuStockMapper.aggregateAvailableStock(
                        validProducts.stream().map(PmsProduct::getId).distinct().toList()
                ).stream()
                .collect(Collectors.toMap(ProductStockAggregate::getProductId, Function.identity()));
        Map<Long, Integer> result = new LinkedHashMap<>();
        for (PmsProduct product : validProducts) {
            ProductStockAggregate aggregate = aggregates.get(product.getId());
            if (aggregate != null && aggregate.getSkuCount() != null && aggregate.getSkuCount() > 0) {
                result.put(product.getId(), Math.max(0, aggregate.getAvailableStock() == null ? 0 : aggregate.getAvailableStock()));
            } else {
                int stock = product.getStock() == null ? 0 : product.getStock();
                int locked = product.getLockStock() == null ? 0 : product.getLockStock();
                result.put(product.getId(), Math.max(0, stock - locked));
            }
        }
        return result;
    }

    @Override
    public PmsProduct getById(Long id) {
        return productMapper.selectById(id);
    }

    @Override
    public PmsProduct create(PmsProduct product) {
        if (product.getDeleteStatus() == null) product.setDeleteStatus(0);
        if (product.getPublishStatus() == null) product.setPublishStatus(1);
        if (product.getNewStatus() == null) product.setNewStatus(0);
        if (product.getRecommandStatus() == null) product.setRecommandStatus(0);
        if (product.getVerifyStatus() == null) product.setVerifyStatus(1);
        if (product.getSort() == null) product.setSort(0);
        if (product.getSale() == null) product.setSale(0);
        if (product.getLowStock() == null) product.setLowStock(0);
        if (product.getUnit() == null) product.setUnit("件");
        productMapper.insert(product);
        return product;
    }

    @Override
    public PmsProduct update(PmsProduct product) {
        PmsProduct existing = productMapper.selectById(product.getId());
        if (existing == null) {
            throw new RuntimeException("商品不存在");
        }
        if (product.getName() != null) existing.setName(product.getName());
        if (product.getCategoryId() != null) existing.setCategoryId(product.getCategoryId());
        if (product.getBrandId() != null) existing.setBrandId(product.getBrandId());
        if (product.getBrandName() != null) existing.setBrandName(product.getBrandName());
        if (product.getProductCategoryName() != null) existing.setProductCategoryName(product.getProductCategoryName());
        if (product.getSubTitle() != null) existing.setSubTitle(product.getSubTitle());
        if (product.getDescription() != null) existing.setDescription(product.getDescription());
        if (product.getDetailDesc() != null) existing.setDetailDesc(product.getDetailDesc());
        if (product.getKeywords() != null) existing.setKeywords(product.getKeywords());
        if (product.getPic() != null) existing.setPic(product.getPic());
        if (product.getProductSn() != null) existing.setProductSn(product.getProductSn());
        if (product.getPrice() != null) existing.setPrice(product.getPrice());
        if (product.getOriginalPrice() != null) existing.setOriginalPrice(product.getOriginalPrice());
        if (product.getPromotionPrice() != null) existing.setPromotionPrice(product.getPromotionPrice());
        if (product.getStock() != null) existing.setStock(product.getStock());
        if (product.getLowStock() != null) existing.setLowStock(product.getLowStock());
        if (product.getPublishStatus() != null) existing.setPublishStatus(product.getPublishStatus());
        if (product.getNewStatus() != null) existing.setNewStatus(product.getNewStatus());
        if (product.getRecommandStatus() != null) existing.setRecommandStatus(product.getRecommandStatus());
        if (product.getVerifyStatus() != null) existing.setVerifyStatus(product.getVerifyStatus());
        if (product.getSort() != null) existing.setSort(product.getSort());
        if (product.getUnit() != null) existing.setUnit(product.getUnit());
        if (product.getWeight() != null) existing.setWeight(product.getWeight());
        productMapper.updateById(existing);
        return existing;
    }

    @Override
    public void delete(Long id) {
        PmsProduct existing = productMapper.selectById(id);
        if (existing == null) {
            throw new RuntimeException("商品不存在");
        }
        existing.setDeleteStatus(1);
        existing.setPublishStatus(0);
        productMapper.updateById(existing);
    }

    private LambdaQueryWrapper<PmsProduct> basePublishedWrapper() {
        return new LambdaQueryWrapper<PmsProduct>()
                .eq(PmsProduct::getDeleteStatus, 0)
                .eq(PmsProduct::getPublishStatus, 1);
    }

    private LambdaQueryWrapper<PmsProduct> listWrapper(Long categoryId, String keyword, Boolean inStock) {
        LambdaQueryWrapper<PmsProduct> wrapper = basePublishedWrapper();
        if (categoryId != null) {
            wrapper.eq(PmsProduct::getCategoryId, categoryId);
        }
        if (keyword != null && !keyword.isBlank()) {
            String normalized = keyword.trim();
            wrapper.and(q -> q.like(PmsProduct::getName, normalized)
                    .or()
                    .like(PmsProduct::getKeywords, normalized)
                    .or()
                    .like(PmsProduct::getSubTitle, normalized)
                    .or()
                    .like(PmsProduct::getDescription, normalized));
        }
        if (Boolean.TRUE.equals(inStock)) {
            wrapper.apply("""
                    ((EXISTS (SELECT 1 FROM pms_sku_stock sku_all WHERE sku_all.product_id = pms_product.id)
                      AND EXISTS (SELECT 1 FROM pms_sku_stock sku_available
                                  WHERE sku_available.product_id = pms_product.id
                                    AND sku_available.status = 1
                                    AND sku_available.stock - sku_available.lock_stock > 0))
                     OR
                     (NOT EXISTS (SELECT 1 FROM pms_sku_stock sku_all WHERE sku_all.product_id = pms_product.id)
                      AND pms_product.stock - pms_product.lock_stock > 0))
                    """);
        }
        return wrapper;
    }

    private String normalizeSort(String sort) {
        if (sort == null || sort.isBlank()) return "DEFAULT";
        return switch (sort.trim().toUpperCase()) {
            case "DEFAULT", "SALES", "PRICE_ASC", "PRICE_DESC", "NEWEST" -> sort.trim().toUpperCase();
            default -> throw new IllegalArgumentException("不支持的商品排序方式");
        };
    }

    private void applySort(LambdaQueryWrapper<PmsProduct> wrapper, String sort, String keyword) {
        switch (sort) {
            case "SALES" -> wrapper.orderByDesc(PmsProduct::getSale).orderByAsc(PmsProduct::getId);
            case "NEWEST" -> wrapper.orderByDesc(PmsProduct::getCreateTime).orderByDesc(PmsProduct::getId);
            default -> wrapper
                    .orderByDesc(keyword != null && !keyword.isBlank(), PmsProduct::getSale)
                    .orderByDesc(keyword == null || keyword.isBlank(), PmsProduct::getSort)
                    .orderByAsc(PmsProduct::getId);
        }
    }

    private String limitClause(Integer limit) {
        int safeLimit = (limit == null || limit <= 0) ? 6 : limit;
        return "limit " + safeLimit;
    }
}
