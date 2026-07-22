package com.aimall.server.product;
import com.aimall.server.common.ApiResponse; import com.aimall.server.entity.PmsProductReview; import com.aimall.server.service.impl.ProductInteractionService; import org.springframework.web.bind.annotation.*; import java.util.List;
@RestController @RequestMapping("/api/products/{productId}/reviews") public class ProductReviewController{
 private final ProductInteractionService s; public ProductReviewController(ProductInteractionService s){this.s=s;}
 @GetMapping public ApiResponse<List<PmsProductReview>> list(@PathVariable Long productId){return ApiResponse.success(s.reviews(productId));}
}
