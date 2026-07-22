package com.aimall.server.home;

import com.aimall.server.common.ApiResponse;
import com.aimall.server.entity.PmsProduct;
import com.aimall.server.entity.PmsProductCategory;
import com.aimall.server.service.ProductService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/home")
public class HomeController {

    private final ProductService productService;

    public HomeController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/content")
    public ApiResponse<Map<String, Object>> content() {
        Map<String, Object> data = new HashMap<>();
        data.put("categoryList", productService.listHomeCategories().stream().map(this::toCategoryMap).collect(Collectors.toList()));
        data.put("recommendProductList", productService.listRecommendProducts(6).stream().map(this::toProductMap).collect(Collectors.toList()));
        data.put("newProductList", productService.listNewProducts(4).stream().map(this::toProductMap).collect(Collectors.toList()));
        data.put("hotProductList", productService.listHotProducts(4).stream().map(this::toProductMap).collect(Collectors.toList()));
        return ApiResponse.success(data);
    }

    @GetMapping("/categories")
    public ApiResponse<List<Map<String, Object>>> categories() {
        return ApiResponse.success(productService.listHomeCategories().stream().map(this::toCategoryMap).collect(Collectors.toList()));
    }

    private Map<String, Object> toCategoryMap(PmsProductCategory category) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", category.getId());
        data.put("name", category.getName());
        data.put("level", category.getLevel());
        data.put("keywords", category.getKeywords());
        data.put("description", category.getDescription());
        return data;
    }

    private Map<String, Object> toProductMap(PmsProduct product) {
        Map<String, Object> data = new HashMap<>();
        data.put("productId", product.getId());
        data.put("name", product.getName());
        data.put("brandName", product.getBrandName());
        data.put("categoryName", product.getProductCategoryName());
        data.put("price", product.getPromotionPrice() != null ? product.getPromotionPrice() : product.getPrice());
        data.put("originalPrice", product.getOriginalPrice());
        data.put("subTitle", product.getSubTitle());
        data.put("pic", product.getPic());
        return data;
    }
}
