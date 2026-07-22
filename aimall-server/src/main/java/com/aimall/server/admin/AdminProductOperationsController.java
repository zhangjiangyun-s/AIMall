package com.aimall.server.admin;

import com.aimall.server.common.ApiResponse;
import com.aimall.server.entity.PmsPriceHistory;
import com.aimall.server.entity.ProductStockAlert;
import com.aimall.server.entity.PmsProduct;
import com.aimall.server.entity.PmsProductPriceRule;
import com.aimall.server.mapper.PmsSkuStockMapper;
import com.aimall.server.service.AdminService;
import com.aimall.server.service.impl.ProductPriceHistoryService;
import com.aimall.server.service.impl.ProductPriceRuleService;
import com.aimall.server.security.AdminPermissions;
import com.aimall.server.security.RequireAdminPermission;
import com.aimall.server.admin.dto.PublishStateRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/product-operations")
@RequireAdminPermission(AdminPermissions.PRODUCT_VIEW)
public class AdminProductOperationsController {
    private final PmsSkuStockMapper skuMapper;
    private final ProductPriceHistoryService priceHistoryService;
    private final AdminService adminService;
    private final ProductPriceRuleService priceRuleService;

    public AdminProductOperationsController(PmsSkuStockMapper skuMapper,
                                            ProductPriceHistoryService priceHistoryService,
                                            AdminService adminService,
                                            ProductPriceRuleService priceRuleService) {
        this.skuMapper = skuMapper;
        this.priceHistoryService = priceHistoryService;
        this.adminService = adminService;
        this.priceRuleService = priceRuleService;
    }

    @GetMapping("/stock-alerts")
    public ApiResponse<List<ProductStockAlert>> stockAlerts(@RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(skuMapper.listLowStockAlerts(Math.max(1, Math.min(limit, 200))));
    }

    @GetMapping("/products/{productId}/price-history")
    public ApiResponse<List<PmsPriceHistory>> priceHistory(
            @PathVariable Long productId,
            @RequestParam(required = false) Long skuId,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(priceHistoryService.list(productId, skuId, limit));
    }

    @PostMapping("/products/{productId}/publish-state")
    @RequireAdminPermission(AdminPermissions.PRODUCT_EDIT)
    public ApiResponse<PmsProduct> changePublishState(@PathVariable Long productId,
                                                      @Valid @RequestBody PublishStateRequest params) {
        return ApiResponse.success(adminService.changeProductPublishStatus(productId, params.published()));
    }

    @GetMapping("/products/{productId}/price-rules")
    public ApiResponse<List<PmsProductPriceRule>> priceRules(@PathVariable Long productId) {
        return ApiResponse.success(priceRuleService.list(productId));
    }

    @PostMapping("/products/{productId}/price-rules")
    @RequireAdminPermission(AdminPermissions.PRODUCT_EDIT)
    public ApiResponse<PmsProductPriceRule> savePriceRule(@PathVariable Long productId,
                                                          @RequestBody PmsProductPriceRule rule) {
        rule.setProductId(productId);
        return ApiResponse.success(priceRuleService.save(rule));
    }

    @PostMapping("/price-rules/{ruleId}/disable")
    @RequireAdminPermission(AdminPermissions.PRODUCT_EDIT)
    public ApiResponse<Void> disablePriceRule(@PathVariable Long ruleId) {
        priceRuleService.disable(ruleId);
        return ApiResponse.success(null);
    }
}
