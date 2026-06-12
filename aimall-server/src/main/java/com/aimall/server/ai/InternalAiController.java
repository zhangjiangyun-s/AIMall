package com.aimall.server.ai;

import com.aimall.server.common.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Hidden
@RestController
@RequestMapping("/internal/ai")
public class InternalAiController {

    @GetMapping("/products/{productId}")
    public ApiResponse<?> getProduct(@PathVariable Integer productId) {
        Map<String, Object> product = getInternalProduct(productId);
        if (product == null) {
            return ApiResponse.fail("商品不存在");
        }
        return ApiResponse.success(product);
    }

    private Map<String, Object> getInternalProduct(Integer productId) {
        return switch (productId) {
            case 1001 -> Map.of(
                    "productId", 1001,
                    "name", "学习平板 A1",
                    "price", 2999,
                    "stock", 88,
                    "sellingPoints", List.of("护眼屏", "手写笔", "适合学习")
            );
            case 1002 -> Map.of(
                    "productId", 1002,
                    "name", "轻薄笔记本 B2",
                    "price", 3999,
                    "stock", 55,
                    "sellingPoints", List.of("轻薄", "长续航", "适合办公")
            );
            case 1003 -> Map.of(
                    "productId", 1003,
                    "name", "无线蓝牙耳机 C3",
                    "price", 399,
                    "stock", 200,
                    "sellingPoints", List.of("降噪", "长续航", "通勤")
            );
            default -> null;
        };
    }
}
