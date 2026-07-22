package com.aimall.server.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.aimall.server.entity.PmsPriceHistory;
import com.aimall.server.mapper.PmsPriceHistoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ProductPriceHistoryService {
    private final PmsPriceHistoryMapper mapper;

    public ProductPriceHistoryService(PmsPriceHistoryMapper mapper) {
        this.mapper = mapper;
    }

    public void recordProduct(Long productId, String priceType, BigDecimal oldAmount,
                              BigDecimal newAmount, String reason) {
        record("PRODUCT", productId, null, priceType, oldAmount, newAmount, reason);
    }

    public void recordSku(Long productId, Long skuId, String priceType, BigDecimal oldAmount,
                          BigDecimal newAmount, String reason) {
        record("SKU", productId, skuId, priceType, oldAmount, newAmount, reason);
    }

    public List<PmsPriceHistory> list(Long productId, Long skuId, int limit) {
        LambdaQueryWrapper<PmsPriceHistory> query = new LambdaQueryWrapper<PmsPriceHistory>()
                .eq(PmsPriceHistory::getProductId, productId)
                .orderByDesc(PmsPriceHistory::getId)
                .last("limit " + Math.max(1, Math.min(limit, 200)));
        if (skuId != null) query.eq(PmsPriceHistory::getSkuId, skuId);
        return mapper.selectList(query);
    }

    private void record(String targetType, Long productId, Long skuId, String priceType,
                        BigDecimal oldAmount, BigDecimal newAmount, String reason) {
        if (same(oldAmount, newAmount)) return;
        PmsPriceHistory value = new PmsPriceHistory();
        value.setTargetType(targetType);
        value.setProductId(productId);
        value.setSkuId(skuId);
        value.setPriceType(priceType);
        value.setOldAmount(oldAmount);
        value.setNewAmount(newAmount);
        value.setOperatorId(operatorId());
        value.setChangeReason(reason);
        value.setCreateTime(LocalDateTime.now());
        mapper.insert(value);
    }

    private Long operatorId() {
        if (!StpUtil.isLogin()) return null;
        String loginId = StpUtil.getLoginIdAsString();
        if (loginId == null || !loginId.startsWith("admin_")) return null;
        return Long.parseLong(loginId.substring("admin_".length()));
    }

    private boolean same(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }
}
