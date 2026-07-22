package com.aimall.server.cart;

import cn.dev33.satoken.stp.StpUtil;
import com.aimall.server.common.ApiResponse;
import com.aimall.server.common.RequestUtils;
import com.aimall.server.cart.dto.CartAddRequest;
import com.aimall.server.cart.dto.CartDeleteRequest;
import com.aimall.server.cart.dto.CartUpdateRequest;
import com.aimall.server.entity.OmsCartItem;
import com.aimall.server.service.CartService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @PostMapping("/add")
    public ApiResponse<OmsCartItem> add(@Valid @RequestBody CartAddRequest params) {
        long memberId = StpUtil.getLoginIdAsLong();
        OmsCartItem item = cartService.add(
                memberId,
                params.productId(),
                params.productSkuId(),
                params.resolvedQuantity()
        );
        return ApiResponse.success(item);
    }

    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> list() {
        long memberId = StpUtil.getLoginIdAsLong();
        List<Map<String, Object>> data = cartService.list(memberId).stream().map(item -> {
            Map<String, Object> row = new HashMap<>();
            row.put("id", item.getId());
            row.put("productId", item.getProductId());
            row.put("productSkuId", item.getProductSkuId() == null ? 0L : item.getProductSkuId());
            row.put("productName", item.getProductName());
            row.put("productPrice", item.getPrice());
            row.put("quantity", item.getQuantity());
            row.put("productSubTitle", item.getProductSubTitle() == null ? "" : item.getProductSubTitle());
            row.put("productSkuCode", item.getProductSkuCode() == null ? "" : item.getProductSkuCode());
            row.put("productAttr", item.getProductAttr() == null ? "" : item.getProductAttr());
            row.put("createDate", item.getCreateDate() == null ? "" : item.getCreateDate().toString().replace("T", " "));
            return row;
        }).collect(Collectors.toList());
        return ApiResponse.success(data);
    }

    @PostMapping("/update")
    public ApiResponse<Void> update(@Valid @RequestBody CartUpdateRequest params) {
        long memberId = StpUtil.getLoginIdAsLong();
        cartService.updateQuantity(
                memberId,
                params.cartItemId(),
                params.quantity()
        );
        return ApiResponse.success(null);
    }

    @PostMapping("/delete")
    public ApiResponse<Void> delete(@Valid @RequestBody CartDeleteRequest params) {
        long memberId = StpUtil.getLoginIdAsLong();
        cartService.delete(memberId, params.cartItemId());
        return ApiResponse.success(null);
    }
}
