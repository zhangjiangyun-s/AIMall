package com.aimall.server.user;

import cn.dev33.satoken.stp.StpUtil;
import com.aimall.server.common.ApiResponse;
import com.aimall.server.service.CouponService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user/coupons")
public class CouponController {

    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @GetMapping("/available")
    public ApiResponse<List<Map<String, Object>>> available(@RequestParam(value = "goodsAmount", required = false) BigDecimal goodsAmount) {
        return ApiResponse.success(couponService.listAvailableCoupons(
                StpUtil.getLoginIdAsLong(),
                goodsAmount == null ? BigDecimal.ZERO : goodsAmount
        ));
    }

    @GetMapping("/my")
    public ApiResponse<List<Map<String, Object>>> myCoupons() {
        return ApiResponse.success(couponService.listMemberCoupons(StpUtil.getLoginIdAsLong()));
    }

    @GetMapping("/center")
    public ApiResponse<List<Map<String, Object>>> couponCenter() {
        return ApiResponse.success(couponService.listCouponCenter(StpUtil.getLoginIdAsLong()));
    }

    @PostMapping("/{couponId}/claim")
    public ApiResponse<Map<String, Object>> claim(@PathVariable Long couponId) {
        return ApiResponse.success(couponService.claimCoupon(StpUtil.getLoginIdAsLong(), couponId));
    }
}
