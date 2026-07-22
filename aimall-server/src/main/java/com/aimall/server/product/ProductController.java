package com.aimall.server.product;

import com.aimall.server.common.ApiResponse;
import com.aimall.server.common.PageResult;
import com.aimall.server.entity.PmsProduct;
import com.aimall.server.entity.PmsProductCategory;
import com.aimall.server.entity.PmsSkuStock;
import com.aimall.server.service.ProductService;
import com.aimall.server.exception.ResourceNotFoundException;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "inStock", required = false) Boolean inStock,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size
    ) {
        PageResult<PmsProduct> products = productService.pageProducts(
                categoryId, keyword, sort, inStock, page, size
        );
        return ApiResponse.success(PageResult.of(
                products.getList().stream().map(this::toListItem).collect(Collectors.toList()),
                products.getTotal(),
                products.getPage(),
                products.getSize()
        ));
    }

    @GetMapping("/recommend")
    public ApiResponse<List<Map<String, Object>>> recommend(@RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.success(productService.listRecommendProducts(limit).stream().map(this::toListItem).collect(Collectors.toList()));
    }

    @GetMapping("/new")
    public ApiResponse<List<Map<String, Object>>> newest(@RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.success(productService.listNewProducts(limit).stream().map(this::toListItem).collect(Collectors.toList()));
    }

    @GetMapping("/hot")
    public ApiResponse<List<Map<String, Object>>> hot(@RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.success(productService.listHotProducts(limit).stream().map(this::toListItem).collect(Collectors.toList()));
    }

    @GetMapping("/categories")
    public ApiResponse<List<Map<String, Object>>> categories(@RequestParam(value = "parentId", required = false) Long parentId) {
        List<PmsProductCategory> categories = productService.listCategories(parentId);
        return ApiResponse.success(categories.stream().map(category -> {
            Map<String, Object> data = new HashMap<>();
            data.put("id", category.getId());
            data.put("name", category.getName());
            data.put("parentId", category.getParentId());
            data.put("level", category.getLevel());
            data.put("keywords", category.getKeywords());
            data.put("description", category.getDescription());
            return data;
        }).collect(Collectors.toList()));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        PmsProduct product = productService.getById(id);
        if (product == null
                || product.getDeleteStatus() == null
                || product.getDeleteStatus() != 0
                || product.getPublishStatus() == null
                || product.getPublishStatus() != 1
                || product.getVerifyStatus() == null
                || product.getVerifyStatus() != 1) {
            throw new ResourceNotFoundException("商品不存在");
        }
        List<PmsSkuStock> skuStocks = productService.listSkuStocks(id);
        return ApiResponse.success(toDetailItem(product, skuStocks));
    }

    private Map<String, Object> toListItem(PmsProduct product) {
        Map<String, Object> data = new HashMap<>();
        data.put("productId", product.getId());
        data.put("name", product.getName());
        data.put("price", product.getPromotionPrice() != null ? product.getPromotionPrice() : product.getPrice());
        data.put("originalPrice", product.getOriginalPrice());
        data.put("category", product.getProductCategoryName());
        data.put("categoryId", product.getCategoryId());
        data.put("subTitle", product.getSubTitle());
        data.put("pic", product.getPic());
        data.put("brandName", product.getBrandName());
        data.put("sellingPoints", sellingPoints(product));
        return data;
    }

    private Map<String, Object> toDetailItem(PmsProduct product, List<PmsSkuStock> skuStocks) {
        Map<String, Object> data = new HashMap<>();
        data.put("productId", product.getId());
        data.put("name", product.getName());
        data.put("price", product.getPromotionPrice() != null ? product.getPromotionPrice() : product.getPrice());
        data.put("originalPrice", product.getOriginalPrice());
        data.put("pic", product.getPic());
        data.put("category", product.getProductCategoryName());
        data.put("categoryId", product.getCategoryId());
        data.put("brandName", product.getBrandName());
        data.put("stock", productService.availableStock(product));
        data.put("description", product.getDescription());
        data.put("detailDesc", product.getDetailDesc());
        data.put("subTitle", product.getSubTitle());
        data.put("keywords", product.getKeywords());
        data.put("sellingPoints", sellingPoints(product));
        data.put("skuStocks", skuStocks.stream().map(sku -> {
            Map<String, Object> skuMap = new HashMap<>();
            skuMap.put("id", sku.getId());
            skuMap.put("skuCode", sku.getSkuCode());
            skuMap.put("price", sku.getPromotionPrice() != null ? sku.getPromotionPrice() : sku.getPrice());
            skuMap.put("stock", available(sku.getStock(), sku.getLockStock()));
            skuMap.put("sale", sku.getSale());
            skuMap.put("spData", sku.getSpData());
            return skuMap;
        }).collect(Collectors.toList()));
        return data;
    }

    private List<String> sellingPoints(PmsProduct product) {
        return List.of(
                product.getSubTitle() == null ? "" : product.getSubTitle(),
                product.getBrandName() == null ? "" : product.getBrandName(),
                product.getKeywords() == null ? "" : product.getKeywords()
        ).stream().filter(text -> text != null && !text.isBlank()).distinct().limit(3).collect(Collectors.toList());
    }

    private int available(Integer stock, Integer lockStock) {
        return Math.max(0, (stock == null ? 0 : stock) - (lockStock == null ? 0 : lockStock));
    }
}
