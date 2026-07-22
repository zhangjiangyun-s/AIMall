package com.aimall.server.service.impl;

import com.aimall.server.entity.SmsCoupon;
import com.aimall.server.entity.UmsMemberCoupon;
import com.aimall.server.mapper.SmsCouponMapper;
import com.aimall.server.mapper.UmsMemberCouponMapper;
import com.aimall.server.service.CartPricingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;

class CouponServiceImplTest {

    @Test
    void claimReservesGlobalIssuanceBeforeCreatingMemberClaim() {
        UmsMemberCouponMapper memberMapper = mock(UmsMemberCouponMapper.class);
        SmsCouponMapper couponMapper = mock(SmsCouponMapper.class);
        CouponServiceImpl service = new CouponServiceImpl(memberMapper, couponMapper, mock(CartPricingService.class));
        SmsCoupon coupon = new SmsCoupon();
        coupon.setId(10L);
        coupon.setName("满减券");
        coupon.setType("FULL_REDUCTION");
        coupon.setAmount(new BigDecimal("20.00"));
        coupon.setMinPoint(new BigDecimal("100.00"));
        coupon.setPlatform("ALL");
        coupon.setStatus(1);
        coupon.setStartTime(LocalDateTime.now().minusDays(1));
        coupon.setEndTime(LocalDateTime.now().plusDays(1));
        when(couponMapper.selectById(10L)).thenReturn(coupon);
        when(couponMapper.reserveIssuance(10L)).thenReturn(1);
        when(memberMapper.insertClaim(any())).thenAnswer(invocation -> {
            UmsMemberCoupon memberCoupon = invocation.getArgument(0);
            memberCoupon.setId(99L);
            return 1;
        });

        var result = service.claimCoupon(1L, 10L);

        assertEquals(99L, result.get("memberCouponId"));
        var ordered = inOrder(couponMapper, memberMapper);
        ordered.verify(couponMapper).reserveIssuance(10L);
        ordered.verify(memberMapper).insertClaim(any());
    }

    @Test
    void budgetUsesActualOrderDiscountInsteadOfCouponFaceValue() {
        UmsMemberCouponMapper memberMapper = mock(UmsMemberCouponMapper.class);
        SmsCouponMapper couponMapper = mock(SmsCouponMapper.class);
        CouponServiceImpl service = new CouponServiceImpl(memberMapper, couponMapper, mock(CartPricingService.class));
        UmsMemberCoupon memberCoupon = new UmsMemberCoupon();
        memberCoupon.setId(9L);
        memberCoupon.setMemberId(1L);
        memberCoupon.setCouponId(10L);
        memberCoupon.setStatus(0);
        memberCoupon.setUsageState("AVAILABLE");
        SmsCoupon coupon = new SmsCoupon();
        coupon.setId(10L);
        coupon.setAmount(new BigDecimal("20.00"));
        when(memberMapper.selectById(9L)).thenReturn(memberCoupon);
        when(couponMapper.selectById(10L)).thenReturn(coupon);
        when(memberMapper.lockForOrder(9L, 1L, 100L, "ORDER-100")).thenReturn(1);
        when(couponMapper.consumeBudget(10L, new BigDecimal("8.0000"))).thenReturn(1);
        when(memberMapper.consumeLocked(9L, 1L, 100L, new BigDecimal("8.0000"))).thenReturn(1);

        service.markCouponUsed(1L, 9L, 100L, "ORDER-100", new BigDecimal("8.00"));

        var ordered = inOrder(memberMapper, couponMapper);
        ordered.verify(memberMapper).lockForOrder(9L, 1L, 100L, "ORDER-100");
        ordered.verify(couponMapper).consumeBudget(10L, new BigDecimal("8.0000"));
        ordered.verify(memberMapper).consumeLocked(9L, 1L, 100L, new BigDecimal("8.0000"));
    }
}
