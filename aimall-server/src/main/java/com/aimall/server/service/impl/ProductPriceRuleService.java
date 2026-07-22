package com.aimall.server.service.impl;

import com.aimall.server.entity.PmsProductPriceRule;
import com.aimall.server.entity.UmsMember;
import com.aimall.server.mapper.OmsOrderItemMapper;
import com.aimall.server.mapper.PmsProductPriceRuleMapper;
import com.aimall.server.mapper.PmsProductMapper;
import com.aimall.server.mapper.PmsSkuStockMapper;
import com.aimall.server.mapper.UmsMemberMapper;
import com.aimall.server.money.MoneyPolicy;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
public class ProductPriceRuleService {
    private final PmsProductPriceRuleMapper ruleMapper;
    private final UmsMemberMapper memberMapper;
    private final OmsOrderItemMapper orderItemMapper;
    private final PmsProductMapper productMapper;
    private final PmsSkuStockMapper skuMapper;

    public ProductPriceRuleService(PmsProductPriceRuleMapper ruleMapper,
                                   UmsMemberMapper memberMapper,
                                   OmsOrderItemMapper orderItemMapper,
                                   PmsProductMapper productMapper,
                                   PmsSkuStockMapper skuMapper) {
        this.ruleMapper = ruleMapper;
        this.memberMapper = memberMapper;
        this.orderItemMapper = orderItemMapper;
        this.productMapper = productMapper;
        this.skuMapper = skuMapper;
    }

    public PriceResolution resolve(Long memberId, Long productId, Long skuId,
                                   BigDecimal baselinePrice, int quantity) {
        LocalDateTime now = LocalDateTime.now();
        UmsMember member = memberMapper.selectById(memberId);
        String memberLevel = member == null || member.getMemberLevel() == null
                ? "NORMAL" : member.getMemberLevel();
        List<PmsProductPriceRule> applicable = ruleMapper.selectList(
                new LambdaQueryWrapper<PmsProductPriceRule>()
                        .eq(PmsProductPriceRule::getProductId, productId)
                        .eq(PmsProductPriceRule::getStatus, 1)
                        .le(PmsProductPriceRule::getStartTime, now)
                        .ge(PmsProductPriceRule::getEndTime, now)
                        .and(query -> query.isNull(PmsProductPriceRule::getSkuId)
                                .or(skuId != null).eq(skuId != null, PmsProductPriceRule::getSkuId, skuId))
        ).stream().filter(rule -> eligible(rule, memberLevel)).toList();

        PmsProductPriceRule selected = applicable.stream()
                .filter(rule -> rule.getPrice() != null && rule.getPrice().compareTo(baselinePrice) < 0)
                .min(Comparator.comparing(PmsProductPriceRule::getPrice)
                        .thenComparing(PmsProductPriceRule::getPriority,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PmsProductPriceRule::getId))
                .orElse(null);
        if (selected != null) {
            enforceLimit(selected, memberId, productId, skuId, quantity);
        }
        return selected == null
                ? new PriceResolution(baselinePrice, null, null)
                : new PriceResolution(selected.getPrice(), selected.getRuleName(), selected.getId());
    }

    public List<PmsProductPriceRule> list(Long productId) {
        return ruleMapper.selectList(new LambdaQueryWrapper<PmsProductPriceRule>()
                .eq(PmsProductPriceRule::getProductId, productId)
                .orderByDesc(PmsProductPriceRule::getStatus)
                .orderByDesc(PmsProductPriceRule::getPriority)
                .orderByDesc(PmsProductPriceRule::getId));
    }

    @Transactional
    public PmsProductPriceRule save(PmsProductPriceRule rule) {
        validate(rule);
        LocalDateTime now = LocalDateTime.now();
        if (rule.getId() == null) {
            rule.setCreateTime(now);
            rule.setUpdateTime(now);
            ruleMapper.insert(rule);
        } else {
            rule.setUpdateTime(now);
            if (ruleMapper.updateById(rule) != 1) throw new RuntimeException("价格规则不存在");
        }
        return ruleMapper.selectById(rule.getId());
    }

    public void disable(Long id) {
        PmsProductPriceRule rule = ruleMapper.selectById(id);
        if (rule == null) throw new RuntimeException("价格规则不存在");
        rule.setStatus(0);
        rule.setUpdateTime(LocalDateTime.now());
        ruleMapper.updateById(rule);
    }

    private boolean eligible(PmsProductPriceRule rule, String memberLevel) {
        if ("ACTIVITY".equals(rule.getRuleType())) return true;
        return "MEMBER".equals(rule.getRuleType()) && memberLevel.equals(rule.getMemberLevel());
    }

    private void enforceLimit(PmsProductPriceRule rule, Long memberId, Long productId,
                              Long skuId, int quantity) {
        if (rule.getPerMemberLimit() == null || rule.getPerMemberLimit() <= 0) return;
        int purchased = skuId == null
                ? orderItemMapper.sumPurchasedWithoutSku(memberId, productId, rule.getStartTime(), rule.getEndTime())
                : orderItemMapper.sumPurchasedWithSku(memberId, productId, skuId, rule.getStartTime(), rule.getEndTime());
        if (purchased + quantity > rule.getPerMemberLimit()) {
            throw new RuntimeException("商品超过活动限购数量，当前最多还可购买 "
                    + Math.max(0, rule.getPerMemberLimit() - purchased) + " 件");
        }
    }

    private void validate(PmsProductPriceRule rule) {
        if (rule.getProductId() == null) throw new IllegalArgumentException("商品不能为空");
        var product = productMapper.selectById(rule.getProductId());
        if (product == null || Integer.valueOf(1).equals(product.getDeleteStatus())) {
            throw new IllegalArgumentException("商品不存在");
        }
        BigDecimal basePrice = product.getPromotionPrice() == null ? product.getPrice() : product.getPromotionPrice();
        if (rule.getSkuId() != null) {
            var sku = skuMapper.selectById(rule.getSkuId());
            if (sku == null || !rule.getProductId().equals(sku.getProductId())) {
                throw new IllegalArgumentException("SKU 不属于当前商品");
            }
            basePrice = sku.getPromotionPrice() == null ? sku.getPrice() : sku.getPromotionPrice();
        }
        if (!"MEMBER".equals(rule.getRuleType()) && !"ACTIVITY".equals(rule.getRuleType())) {
            throw new IllegalArgumentException("价格规则类型不支持");
        }
        if ("MEMBER".equals(rule.getRuleType())
                && (rule.getMemberLevel() == null || rule.getMemberLevel().isBlank())) {
            throw new IllegalArgumentException("会员价必须指定会员等级");
        }
        if (rule.getRuleName() == null || rule.getRuleName().isBlank()) throw new IllegalArgumentException("规则名称不能为空");
        if (rule.getPrice() == null || rule.getPrice().compareTo(BigDecimal.ZERO) < 0
                || rule.getPrice().scale() > MoneyPolicy.STORAGE_SCALE) {
            throw new IllegalArgumentException("规则价格必须是非负两位小数");
        }
        if (basePrice == null || rule.getPrice().compareTo(basePrice) > 0) {
            throw new IllegalArgumentException("规则价格不能高于当前销售价");
        }
        if (rule.getStartTime() == null || rule.getEndTime() == null || !rule.getEndTime().isAfter(rule.getStartTime())) {
            throw new IllegalArgumentException("价格规则有效期不正确");
        }
        if (rule.getPerMemberLimit() != null && rule.getPerMemberLimit() < 0) throw new IllegalArgumentException("限购数量不能为负数");
        if (rule.getPriority() == null) rule.setPriority(0);
        if (rule.getStatus() == null) rule.setStatus(1);
        rule.setRuleName(rule.getRuleName().trim());
        rule.setRuleType(rule.getRuleType().trim().toUpperCase());
        if (rule.getMemberLevel() != null) rule.setMemberLevel(rule.getMemberLevel().trim().toUpperCase());
    }

    public record PriceResolution(BigDecimal price, String ruleName, Long ruleId) {
    }
}
