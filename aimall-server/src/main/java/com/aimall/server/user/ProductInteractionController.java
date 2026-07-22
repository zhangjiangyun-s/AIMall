package com.aimall.server.user;

import cn.dev33.satoken.stp.StpUtil;
import com.aimall.server.common.ApiResponse;
import com.aimall.server.entity.PmsProductReview;
import com.aimall.server.entity.UmsMemberBrowseHistory;
import com.aimall.server.entity.UmsMemberProductFavorite;
import com.aimall.server.service.impl.ProductInteractionService;
import com.aimall.server.user.dto.ProductReviewRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user/product-interactions")
public class ProductInteractionController {
    private final ProductInteractionService service;

    public ProductInteractionController(ProductInteractionService service) {
        this.service = service;
    }

    @PostMapping("/{productId}/reviews")
    public ApiResponse<PmsProductReview> review(
            @PathVariable Long productId,
            @Valid @RequestBody ProductReviewRequest request
    ) {
        return ApiResponse.success(service.review(
                StpUtil.getLoginIdAsLong(), productId, request.orderItemId(), request.rating(), request.content()
        ));
    }

    @GetMapping("/{productId}/reviews")
    public ApiResponse<List<PmsProductReview>> reviews(@PathVariable Long productId) {
        return ApiResponse.success(service.reviews(productId));
    }

    @PostMapping("/{productId}/favorite")
    public ApiResponse<Void> favorite(@PathVariable Long productId) {
        service.favorite(StpUtil.getLoginIdAsLong(), productId);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{productId}/favorite")
    public ApiResponse<Void> unfavorite(@PathVariable Long productId) {
        service.unfavorite(StpUtil.getLoginIdAsLong(), productId);
        return ApiResponse.success(null);
    }

    @GetMapping("/favorites")
    public ApiResponse<List<UmsMemberProductFavorite>> favorites() {
        return ApiResponse.success(service.favorites(StpUtil.getLoginIdAsLong()));
    }

    @PostMapping("/{productId}/browse")
    public ApiResponse<Void> browse(@PathVariable Long productId) {
        service.browse(StpUtil.getLoginIdAsLong(), productId);
        return ApiResponse.success(null);
    }

    @GetMapping("/browse-history")
    public ApiResponse<List<UmsMemberBrowseHistory>> history() {
        return ApiResponse.success(service.history(StpUtil.getLoginIdAsLong()));
    }

    @GetMapping("/recommendations")
    public ApiResponse<List<Map<String, Object>>> recommendations() {
        return ApiResponse.success(service.recommendations(StpUtil.getLoginIdAsLong()));
    }
}
