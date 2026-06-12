package com.aimall.server.product;

import com.aimall.server.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "商品")
@RestController
@RequestMapping("/api/products")
public class ProductController {

    @Operation(summary = "商品列表")
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        List<Map<String, Object>> products = List.of(
                Map.of(
                        "productId", 1001,
                        "name", "学习平板 A1",
                        "price", 2999,
                        "category", "平板电脑",
                        "sellingPoints", List.of("护眼屏", "手写笔", "适合学习")
                ),
                Map.of(
                        "productId", 1002,
                        "name", "轻薄笔记本 B2",
                        "price", 3999,
                        "category", "笔记本电脑",
                        "sellingPoints", List.of("轻薄", "长续航", "适合办公")
                ),
                Map.of(
                        "productId", 1003,
                        "name", "无线蓝牙耳机 C3",
                        "price", 399,
                        "category", "耳机",
                        "sellingPoints", List.of("降噪", "长续航", "通勤")
                )
        );
        return ApiResponse.success(products);
    }

    @Operation(summary = "商品详情")
    @GetMapping("/{id}")
    public ApiResponse<?> detail(@PathVariable Integer id) {
        return ApiResponse.success(getProductDetail(id));
    }

    private Map<String, Object> getProductDetail(Integer id) {
        return switch (id) {
            case 1001 -> Map.of(
                    "productId", 1001,
                    "name", "学习平板 A1",
                    "price", 2999,
                    "category", "平板电脑",
                    "stock", 88,
                    "description", "适合学生学习、记笔记和网课使用。",
                    "params", Map.of(
                            "screen", "11 inch",
                            "memory", "8GB",
                            "storage", "256GB"
                    )
            );
            case 1002 -> Map.of(
                    "productId", 1002,
                    "name", "轻薄笔记本 B2",
                    "price", 3999,
                    "category", "笔记本电脑",
                    "stock", 55,
                    "description", "轻薄便携，适合办公和日常使用。",
                    "params", Map.of(
                            "screen", "14 inch",
                            "memory", "16GB",
                            "storage", "512GB"
                    )
            );
            case 1003 -> Map.of(
                    "productId", 1003,
                    "name", "无线蓝牙耳机 C3",
                    "price", 399,
                    "category", "耳机",
                    "stock", 200,
                    "description", "主动降噪，续航强劲，通勤必备。",
                    "params", Map.of(
                            "battery", "30h",
                            "weight", "4.8g",
                            "waterproof", "IPX5"
                    )
            );
            default -> Map.of(
                    "productId", 0,
                    "name", "未知商品 Mock",
                    "price", 0,
                    "category", "未知分类",
                    "stock", 0,
                    "description", "这是兜底 mock 商品。",
                    "params", Map.of(
                            "screen", "unknown",
                            "memory", "unknown",
                            "storage", "unknown"
                    )
            );
        };
    }
}
