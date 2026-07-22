package com.aimall.server.admin;

import com.aimall.server.common.ApiResponse;
import com.aimall.server.entity.*;
import com.aimall.server.service.impl.ProductMetadataService;
import com.aimall.server.security.AdminPermissions;
import com.aimall.server.security.RequireAdminPermission;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/admin/product-metadata")
@RequireAdminPermission(AdminPermissions.PRODUCT_VIEW)
public class AdminProductMetadataController {
    private final ProductMetadataService service;
    public AdminProductMetadataController(ProductMetadataService service) { this.service = service; }
    @GetMapping("/brands") public ApiResponse<List<PmsBrand>> brands() { return ApiResponse.success(service.brands()); }
    @PostMapping("/brands") @RequireAdminPermission(AdminPermissions.PRODUCT_EDIT) public ApiResponse<PmsBrand> saveBrand(@RequestBody PmsBrand brand) { return ApiResponse.success(service.saveBrand(brand)); }
    @GetMapping("/attribute-templates") public ApiResponse<List<PmsCategoryAttributeTemplate>> templates(@RequestParam(required=false) Long categoryId) { return ApiResponse.success(service.templates(categoryId)); }
    @PostMapping("/attribute-templates") @RequireAdminPermission(AdminPermissions.PRODUCT_EDIT) public ApiResponse<PmsCategoryAttributeTemplate> saveTemplate(@RequestBody PmsCategoryAttributeTemplate template) { return ApiResponse.success(service.saveTemplate(template)); }
}
