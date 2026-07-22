package com.aimall.server.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface CouponService {
    List<Map<String, Object>> listAvailableCoupons(Long memberId, BigDecimal goodsAmount);

    List<Map<String, Object>> listMemberCoupons(Long memberId);

    List<Map<String, Object>> listCouponCenter(Long memberId);

    Map<String, Object> claimCoupon(Long memberId, Long couponId);

    Map<String, Object> previewOrder(Long memberId, List<Long> cartItemIds, Long couponId);
}
