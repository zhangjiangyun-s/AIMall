package com.aimall.server.service;

import com.aimall.server.entity.PmsProductImage;
import com.aimall.server.entity.PmsSkuStock;
import com.aimall.server.admin.dto.SkuUpdateRequest;

import java.util.List;

public interface AdminProductCatalogService {
    List<PmsSkuStock> listSkus(Long productId);
    PmsSkuStock createSku(Long productId, PmsSkuStock sku);
    PmsSkuStock updateSku(Long productId, Long skuId, SkuUpdateRequest request);

    PmsSkuStock adjustSkuStock(Long productId, Long skuId, int delta);
    void disableSku(Long productId, Long skuId);
    List<PmsProductImage> listImages(Long productId);
    List<PmsProductImage> replaceImages(Long productId, List<String> imageUrls);
}
