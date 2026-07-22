package com.aimall.server.service.impl;

import com.aimall.server.entity.SmsCoupon;
import com.aimall.server.entity.UmsMemberCoupon;
import com.aimall.server.mapper.SmsCouponMapper;
import com.aimall.server.mapper.UmsMemberCouponMapper;
import com.aimall.server.service.CouponService;
import com.aimall.server.service.CartPricingService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import com.aimall.server.service.CartPricingService.PricedCartItem;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.aimall.server.money.MoneyPolicy;

@Service
public class CouponServiceImpl implements CouponService {

    private final UmsMemberCouponMapper memberCouponMapper;
    private final SmsCouponMapper couponMapper;
    private final CartPricingService cartPricingService;

    public CouponServiceImpl(UmsMemberCouponMapper memberCouponMapper,
                             SmsCouponMapper couponMapper,
                             CartPricingService cartPricingService) {
        this.memberCouponMapper = memberCouponMapper;
        this.couponMapper = couponMapper;
        this.cartPricingService = cartPricingService;
    }

    @Override
    public List<Map<String, Object>> listAvailableCoupons(Long memberId, BigDecimal goodsAmount) {
        BigDecimal currentGoodsAmount = goodsAmount == null ? BigDecimal.ZERO : goodsAmount;
        return memberCouponMapper.selectList(
                new LambdaQueryWrapper<UmsMemberCoupon>()
                        .eq(UmsMemberCoupon::getMemberId, memberId)
                        .eq(UmsMemberCoupon::getStatus, 0)
                        .orderByDesc(UmsMemberCoupon::getId)
        ).stream().map(memberCoupon -> toCouponMap(memberCoupon, currentGoodsAmount)).toList();
    }

    @Override
    public List<Map<String, Object>> listMemberCoupons(Long memberId) {
        return memberCouponMapper.selectList(
                new LambdaQueryWrapper<UmsMemberCoupon>()
                        .eq(UmsMemberCoupon::getMemberId, memberId)
                        .orderByDesc(UmsMemberCoupon::getId)
        ).stream().map(this::toMemberCouponMap).toList();
    }

    @Override
    public List<Map<String, Object>> listCouponCenter(Long memberId) {
        List<Long> ownedCouponIds = memberCouponMapper.selectList(
                new LambdaQueryWrapper<UmsMemberCoupon>()
                        .eq(UmsMemberCoupon::getMemberId, memberId)
        ).stream().map(UmsMemberCoupon::getCouponId).toList();

        return couponMapper.selectList(
                new LambdaQueryWrapper<SmsCoupon>().orderByDesc(SmsCoupon::getId)
        ).stream().sorted(Comparator.comparing(SmsCoupon::getId).reversed()).map(coupon -> {
            Map<String, Object> data = new HashMap<>();
            boolean active = isCouponValid(coupon);
            boolean claimed = ownedCouponIds.contains(coupon.getId());
            data.put("couponId", coupon.getId());
            data.put("name", coupon.getName());
            data.put("type", coupon.getType());
            data.put("amount", coupon.getAmount());
            data.put("minPoint", coupon.getMinPoint());
            data.put("platform", coupon.getPlatform());
            data.put("note", coupon.getNote() == null ? "" : coupon.getNote());
            data.put("startTime", coupon.getStartTime());
            data.put("endTime", coupon.getEndTime());
            data.put("active", active);
            data.put("claimed", claimed);
            data.put("claimable", active && !claimed);
            return data;
        }).toList();
    }

    @Override
    @Transactional
    public Map<String, Object> claimCoupon(Long memberId, Long couponId) {
        SmsCoupon coupon = requireCoupon(couponId);
        if (!isCouponValid(coupon)) {
            throw new RuntimeException("优惠券已失效，暂时不能领取");
        }

        if (couponMapper.reserveIssuance(couponId) != 1) {
            throw new RuntimeException("优惠券已领完或不在领取时间内");
        }
        UmsMemberCoupon memberCoupon = new UmsMemberCoupon();
        memberCoupon.setMemberId(memberId);
        memberCoupon.setCouponId(coupon.getId());
        memberCoupon.setCouponName(coupon.getName());
        memberCoupon.setStatus(0);
        memberCoupon.setCreateTime(LocalDateTime.now());
        if (memberCouponMapper.insertClaim(memberCoupon) != 1) {
            throw new RuntimeException("已达到该优惠券的每人限领数量");
        }
        return toMemberCouponMap(memberCoupon);
    }

    @Override
    public Map<String, Object> previewOrder(Long memberId, List<Long> cartItemIds, Long couponId) {
        if (cartItemIds == null || cartItemIds.isEmpty()) {
            throw new RuntimeException("请选择购物车商品");
        }

        CartPricingService.PriceQuote priceQuote = cartPricingService.quote(memberId, cartItemIds);
        BigDecimal goodsAmount = MoneyPolicy.storage(priceQuote.goodsAmount());

        BigDecimal freightAmount = MoneyPolicy.storage(BigDecimal.ZERO);
        BigDecimal couponAmount = resolveCouponAmount(memberId, couponId, goodsAmount, priceQuote.items());
        BigDecimal payAmount = MoneyPolicy.storage(goodsAmount.add(freightAmount).subtract(couponAmount));
        if (payAmount.compareTo(BigDecimal.ZERO) < 0) {
            payAmount = MoneyPolicy.storage(BigDecimal.ZERO);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("goodsAmount", goodsAmount);
        data.put("freightAmount", freightAmount);
        data.put("couponAmount", couponAmount);
        data.put("discountAmount", couponAmount);
        data.put("payAmount", payAmount);
        data.put("availableCoupons", listAvailableCoupons(memberId, goodsAmount));
        data.put("priceChanges", priceQuote.items().stream()
                .filter(CartPricingService.PricedCartItem::priceChanged)
                .map(item -> Map.of(
                        "cartItemId", item.cartItem().getId(),
                        "productName", item.product().getName(),
                        "oldPrice", item.originalCartPrice(),
                        "currentPrice", item.unitPrice()
                ))
                .toList());
        return data;
    }

    public BigDecimal resolveCouponAmount(Long memberId, Long memberCouponId, BigDecimal goodsAmount) {
        return resolveCouponAmount(memberId, memberCouponId, goodsAmount, List.of());
    }

    public BigDecimal resolveCouponAmount(
            Long memberId,
            Long memberCouponId,
            BigDecimal goodsAmount,
            List<PricedCartItem> items
    ) {
        if (memberCouponId == null) {
            return MoneyPolicy.storage(BigDecimal.ZERO);
        }

        UmsMemberCoupon memberCoupon = requireAvailableMemberCoupon(memberId, memberCouponId);
        SmsCoupon coupon = requireCoupon(memberCoupon.getCouponId());
        if (!isCouponValid(coupon)) {
            throw new RuntimeException("优惠券已失效");
        }
        BigDecimal eligibleAmount = eligibleAmount(coupon, goodsAmount, items);
        if (eligibleAmount.compareTo(coupon.getMinPoint()) < 0) {
            throw new RuntimeException("当前订单未满足优惠券使用门槛");
        }
        return MoneyPolicy.storage(coupon.getAmount().min(eligibleAmount));
    }

    @Transactional
    public void markCouponUsed(Long memberId, Long memberCouponId, Long orderId, String orderSn, BigDecimal actualDiscountAmount) {
        if (memberCouponId == null) {
            return;
        }
        UmsMemberCoupon memberCoupon = requireAvailableMemberCoupon(memberId, memberCouponId);
        SmsCoupon coupon = requireCoupon(memberCoupon.getCouponId());
        BigDecimal actualAmount = MoneyPolicy.storage(actualDiscountAmount);
        if (memberCouponMapper.lockForOrder(memberCouponId, memberId, orderId, orderSn) != 1) {
            throw new RuntimeException("优惠券锁定失败或已绑定其他订单");
        }
        if (couponMapper.consumeBudget(coupon.getId(), actualAmount) != 1) {
            throw new RuntimeException("优惠券活动预算不足");
        }
        if (memberCouponMapper.consumeLocked(memberCouponId, memberId, orderId, actualAmount) != 1) {
            throw new RuntimeException("优惠券已被使用或状态已变化");
        }
    }

    @Transactional
    public void releaseCouponUsage(Long memberId, Long orderId) {
        List<UmsMemberCoupon> used = usedCoupons(memberId, orderId);
        used.forEach(item -> {
            if (memberCouponMapper.releaseConsumed(item.getId(), orderId) == 1) {
                SmsCoupon coupon = requireCoupon(item.getCouponId());
                couponMapper.releaseBudget(coupon.getId(), couponDiscountAmount(item));
            }
        });
    }

    @Transactional
    public void releaseCouponAfterRefund(Long memberId, Long orderId) {
        for (UmsMemberCoupon memberCoupon : usedCoupons(memberId, orderId)) {
            SmsCoupon coupon = requireCoupon(memberCoupon.getCouponId());
            if ("RETURN".equalsIgnoreCase(coupon.getRefundPolicy())) {
                if (memberCouponMapper.restoreConsumed(memberCoupon.getId(), orderId) == 1) {
                    couponMapper.releaseBudget(coupon.getId(), couponDiscountAmount(memberCoupon));
                }
            } else {
                memberCouponMapper.voidConsumed(memberCoupon.getId());
            }
        }
    }

    private Map<String, Object> toCouponMap(UmsMemberCoupon memberCoupon, BigDecimal goodsAmount) {
        SmsCoupon coupon = requireCoupon(memberCoupon.getCouponId());
        boolean active = isCouponValid(coupon);
        boolean available = active && goodsAmount.compareTo(coupon.getMinPoint()) >= 0;

        Map<String, Object> data = new HashMap<>();
        data.put("memberCouponId", memberCoupon.getId());
        data.put("couponId", coupon.getId());
        data.put("name", coupon.getName());
        data.put("type", coupon.getType());
        data.put("amount", coupon.getAmount());
        data.put("minPoint", coupon.getMinPoint());
        data.put("note", coupon.getNote() == null ? "" : coupon.getNote());
        data.put("available", available);
        data.put("unavailableReason", active ? "未达到使用门槛" : "优惠券已失效");
        return data;
    }

    private Map<String, Object> toMemberCouponMap(UmsMemberCoupon memberCoupon) {
        SmsCoupon coupon = requireCoupon(memberCoupon.getCouponId());
        Map<String, Object> data = new HashMap<>();
        boolean active = isCouponValid(coupon);
        data.put("memberCouponId", memberCoupon.getId());
        data.put("couponId", coupon.getId());
        data.put("name", coupon.getName());
        data.put("type", coupon.getType());
        data.put("amount", coupon.getAmount());
        data.put("minPoint", coupon.getMinPoint());
        data.put("platform", coupon.getPlatform());
        data.put("note", coupon.getNote() == null ? "" : coupon.getNote());
        data.put("status", memberCoupon.getStatus());
        data.put("usageState", memberCoupon.getUsageState());
        data.put("usedTime", memberCoupon.getUsedTime());
        data.put("orderId", memberCoupon.getOrderId());
        data.put("orderSn", memberCoupon.getOrderSn());
        data.put("createTime", memberCoupon.getCreateTime());
        data.put("active", active);
        data.put("statusText", memberCoupon.getStatus() != null && memberCoupon.getStatus() == 1 ? "已使用" : (active ? "未使用" : "已失效"));
        return data;
    }

    private UmsMemberCoupon requireAvailableMemberCoupon(Long memberId, Long memberCouponId) {
        UmsMemberCoupon memberCoupon = memberCouponMapper.selectById(memberCouponId);
        String usageState = memberCoupon == null ? null : memberCoupon.getUsageState();
        if (memberCoupon == null || !memberId.equals(memberCoupon.getMemberId()) || memberCoupon.getStatus() != 0
                || !(usageState == null || usageState.equals("AVAILABLE") || usageState.equals("RELEASED")
                     || usageState.equals("RESTORED"))) {
            throw new RuntimeException("优惠券不可用");
        }
        return memberCoupon;
    }

    private SmsCoupon requireCoupon(Long couponId) {
        SmsCoupon coupon = couponMapper.selectById(couponId);
        if (coupon == null) {
            throw new RuntimeException("优惠券不存在");
        }
        return coupon;
    }

    private boolean isCouponValid(SmsCoupon coupon) {
        LocalDateTime now = LocalDateTime.now();
        return coupon.getStatus() != null
                && coupon.getStatus() == 1
                && (coupon.getStartTime() == null || !coupon.getStartTime().isAfter(now))
                && (coupon.getEndTime() == null || !coupon.getEndTime().isBefore(now));
    }

    private BigDecimal eligibleAmount(SmsCoupon coupon, BigDecimal goodsAmount, List<PricedCartItem> items) {
        String scopeType = coupon.getScopeType() == null ? "ALL" : coupon.getScopeType().toUpperCase();
        if ("ALL".equals(scopeType)) {
            return goodsAmount;
        }
        if (items == null || items.isEmpty()) {
            throw new RuntimeException("该优惠券需要结合商品范围校验");
        }
        List<Long> scopeIds = parseScopeIds(coupon.getScopeIds());
        return items.stream()
                .filter(item -> "PRODUCT".equals(scopeType)
                        ? scopeIds.contains(item.product().getId())
                        : "CATEGORY".equals(scopeType) && scopeIds.contains(item.product().getCategoryId()))
                .map(PricedCartItem::lineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<Long> parseScopeIds(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return java.util.Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(item -> !item.isEmpty())
                    .map(Long::valueOf)
                    .toList();
        } catch (NumberFormatException exception) {
            throw new RuntimeException("优惠券适用范围配置错误");
        }
    }

    private List<UmsMemberCoupon> usedCoupons(Long memberId, Long orderId) {
        return memberCouponMapper.selectList(
                new LambdaQueryWrapper<UmsMemberCoupon>()
                        .eq(UmsMemberCoupon::getMemberId, memberId)
                        .eq(UmsMemberCoupon::getOrderId, orderId)
                        .eq(UmsMemberCoupon::getStatus, 1)
        );
    }

    private BigDecimal couponDiscountAmount(UmsMemberCoupon memberCoupon) {
        return memberCoupon.getActualDiscountAmount() == null ? BigDecimal.ZERO : memberCoupon.getActualDiscountAmount();
    }

    @Scheduled(cron = "${aimall.coupon.expiry-cron:0 15 * * * *}")
    public void expireMemberCoupons() {
        memberCouponMapper.expireAvailable();
    }
}
