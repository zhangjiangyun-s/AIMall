package com.aimall.server.admin.dto;

import com.aimall.server.entity.PmsProduct;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ProductCreateRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull @Positive Long categoryId,
        @Size(max = 200) String category,
        @NotNull @DecimalMin("0.00") BigDecimal price,
        @DecimalMin("0.00") BigDecimal promotionPrice,
        @DecimalMin("0.00") BigDecimal originalPrice,
        @NotNull @Min(0) Integer stock,
        @Min(0) Integer lowStock,
        @Size(max = 500) String description,
        @Size(max = 5000) String detailDesc,
        @Size(max = 255) String keywords,
        @Size(max = 255) String subTitle,
        @Size(max = 100) String brandName,
        @NotBlank @Size(max = 64) String productSn,
        @Min(0) @Max(1) Integer publishStatus,
        @Min(0) @Max(1) Integer newStatus,
        @Min(0) @Max(1) Integer recommandStatus,
        Integer sort,
        @Size(max = 1000) String pic,
        @Pattern(regexp = "上架|下架") String status
) {
    public PmsProduct toEntity() {
        PmsProduct product = new PmsProduct();
        product.setName(name.trim());
        product.setCategoryId(categoryId);
        product.setProductCategoryName(category);
        product.setPrice(price);
        product.setPromotionPrice(promotionPrice);
        product.setOriginalPrice(originalPrice);
        product.setStock(stock);
        product.setLowStock(lowStock);
        product.setDescription(description);
        product.setDetailDesc(detailDesc);
        product.setKeywords(keywords);
        product.setSubTitle(subTitle);
        product.setBrandName(brandName);
        product.setProductSn(productSn.trim());
        product.setPublishStatus(publishStatus);
        product.setNewStatus(newStatus);
        product.setRecommandStatus(recommandStatus);
        product.setSort(sort);
        product.setPic(pic);
        if (status != null) product.setPublishStatus("上架".equals(status) ? 1 : 0);
        return product;
    }
}
