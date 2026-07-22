package com.aimall.server.service;

import com.aimall.server.common.PageResult;
import com.aimall.server.entity.PmsProduct;
import com.aimall.server.entity.PmsProductCategory;
import com.aimall.server.entity.PmsSkuStock;

import java.util.List;
import java.util.Map;

public interface ProductService {
    List<PmsProduct> listAll();
    PageResult<PmsProduct> pageProducts(Long categoryId, String keyword, String sort,
                                        Boolean inStock, Integer page, Integer size);
    List<PmsProduct> listByCategory(Long categoryId);
    List<PmsProduct> search(String keyword);
    List<PmsProduct> search(String keyword, Integer limit);
    List<PmsProduct> listRecommendProducts(Integer limit);
    List<PmsProduct> listNewProducts(Integer limit);
    List<PmsProduct> listHotProducts(Integer limit);
    List<PmsProductCategory> listCategories(Long parentId);
    List<PmsProductCategory> listHomeCategories();
    List<PmsSkuStock> listSkuStocks(Long productId);
    boolean isSkuMode(Long productId);
    int availableStock(PmsProduct product);
    Map<Long, Integer> availableStocks(List<PmsProduct> products);
    PmsProduct getById(Long id);
    PmsProduct create(PmsProduct product);
    PmsProduct update(PmsProduct product);
    void delete(Long id);
}
