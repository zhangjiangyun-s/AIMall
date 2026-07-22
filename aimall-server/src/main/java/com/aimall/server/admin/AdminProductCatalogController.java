package com.aimall.server.admin;

import com.aimall.server.common.ApiResponse;
import com.aimall.server.admin.dto.SkuUpdateRequest;
import com.aimall.server.admin.dto.StockAdjustmentRequest;
import com.aimall.server.entity.PmsProductImage;
import com.aimall.server.entity.PmsSkuStock;
import com.aimall.server.service.AdminProductCatalogService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.aimall.server.common.RequestUtils;
import com.aimall.server.security.AdminPermissions;
import com.aimall.server.security.RequireAdminPermission;

import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import com.aimall.server.entity.InventoryBalance;
import com.aimall.server.mapper.InventoryBalanceMapper;

@RestController
@RequestMapping("/api/admin/products/{productId}")
@RequireAdminPermission(AdminPermissions.PRODUCT_VIEW)
public class AdminProductCatalogController {

    private final AdminProductCatalogService catalogService;
    private final InventoryBalanceMapper inventoryBalanceMapper;

    public AdminProductCatalogController(AdminProductCatalogService catalogService,
                                         InventoryBalanceMapper inventoryBalanceMapper) {
        this.catalogService = catalogService;
        this.inventoryBalanceMapper = inventoryBalanceMapper;
    }

    @GetMapping("/inventory-balance")
    public ApiResponse<List<InventoryBalance>> inventoryBalance(@PathVariable Long productId) {
        return ApiResponse.success(inventoryBalanceMapper.listByProductId(productId));
    }

    @GetMapping("/skus")
    public ApiResponse<List<PmsSkuStock>> listSkus(@PathVariable Long productId) {
        return ApiResponse.success(catalogService.listSkus(productId));
    }

    @PostMapping("/skus")
    @RequireAdminPermission(AdminPermissions.PRODUCT_EDIT)
    public ApiResponse<PmsSkuStock> createSku(@PathVariable Long productId, @RequestBody PmsSkuStock sku) {
        return ApiResponse.success(catalogService.createSku(productId, sku));
    }

    @PutMapping("/skus/{skuId}")
    @RequireAdminPermission(AdminPermissions.PRODUCT_EDIT)
    public ApiResponse<PmsSkuStock> updateSku(
            @PathVariable Long productId,
            @PathVariable Long skuId,
            @RequestBody SkuUpdateRequest sku
    ) {
        return ApiResponse.success(catalogService.updateSku(productId, skuId, sku));
    }

    @DeleteMapping("/skus/{skuId}")
    @RequireAdminPermission(AdminPermissions.PRODUCT_EDIT)
    public ApiResponse<Void> disableSku(@PathVariable Long productId, @PathVariable Long skuId) {
        catalogService.disableSku(productId, skuId);
        return ApiResponse.success(null);
    }

    @PostMapping("/skus/{skuId}/stock-adjustments")
    @RequireAdminPermission(AdminPermissions.STOCK_ADJUST)
    public ApiResponse<PmsSkuStock> adjustSkuStock(
            @PathVariable Long productId,
            @PathVariable Long skuId,
            @Valid @RequestBody StockAdjustmentRequest params
    ) {
        return ApiResponse.success(catalogService.adjustSkuStock(
                productId,
                skuId,
                params.delta()
        ));
    }

    @GetMapping("/images")
    public ApiResponse<List<PmsProductImage>> listImages(@PathVariable Long productId) {
        return ApiResponse.success(catalogService.listImages(productId));
    }

    @PutMapping("/images")
    @RequireAdminPermission(AdminPermissions.PRODUCT_EDIT)
    public ApiResponse<List<PmsProductImage>> replaceImages(
            @PathVariable Long productId,
            @RequestBody List<String> imageUrls
    ) {
        return ApiResponse.success(catalogService.replaceImages(productId, imageUrls));
    }
}
