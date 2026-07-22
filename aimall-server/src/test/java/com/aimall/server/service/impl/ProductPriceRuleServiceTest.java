package com.aimall.server.service.impl;

import com.aimall.server.entity.PmsProduct;
import com.aimall.server.entity.PmsProductPriceRule;
import com.aimall.server.entity.UmsMember;
import com.aimall.server.mapper.OmsOrderItemMapper;
import com.aimall.server.mapper.PmsProductMapper;
import com.aimall.server.mapper.PmsProductPriceRuleMapper;
import com.aimall.server.mapper.PmsSkuStockMapper;
import com.aimall.server.mapper.UmsMemberMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductPriceRuleServiceTest {
    @Test
    void resolvesLowestEligibleMemberOrActivityPrice() {
        Fixture fixture = fixture();
        UmsMember member = new UmsMember();
        member.setId(7L);
        member.setMemberLevel("GOLD");
        when(fixture.memberMapper.selectById(7L)).thenReturn(member);
        when(fixture.ruleMapper.selectList(any())).thenReturn(List.of(
                rule(1L, "MEMBER", "GOLD", "金卡价", "80.00", null),
                rule(2L, "MEMBER", "SILVER", "银卡价", "70.00", null),
                rule(3L, "ACTIVITY", null, "限时活动", "75.00", null)
        ));

        var result = fixture.service.resolve(7L, 10L, null, new BigDecimal("100.00"), 1);

        assertEquals(new BigDecimal("75.0000"), result.price());
        assertEquals("限时活动", result.ruleName());
        assertEquals(3L, result.ruleId());
    }

    @Test
    void rejectsQuantityBeyondHistoricalMemberLimit() {
        Fixture fixture = fixture();
        UmsMember member = new UmsMember();
        member.setMemberLevel("NORMAL");
        when(fixture.memberMapper.selectById(7L)).thenReturn(member);
        when(fixture.ruleMapper.selectList(any())).thenReturn(List.of(
                rule(4L, "ACTIVITY", null, "每人限购三件", "90.00", 3)
        ));
        when(fixture.orderItemMapper.sumPurchasedWithoutSku(any(), any(), any(), any())).thenReturn(2);

        assertThrows(RuntimeException.class,
                () -> fixture.service.resolve(7L, 10L, null, new BigDecimal("100.00"), 2));
    }

    private Fixture fixture() {
        PmsProductPriceRuleMapper ruleMapper = mock(PmsProductPriceRuleMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        OmsOrderItemMapper orderItemMapper = mock(OmsOrderItemMapper.class);
        PmsProductMapper productMapper = mock(PmsProductMapper.class);
        PmsSkuStockMapper skuMapper = mock(PmsSkuStockMapper.class);
        PmsProduct product = new PmsProduct();
        product.setId(10L);
        product.setPrice(new BigDecimal("100.00"));
        product.setDeleteStatus(0);
        when(productMapper.selectById(10L)).thenReturn(product);
        return new Fixture(ruleMapper, memberMapper, orderItemMapper,
                new ProductPriceRuleService(ruleMapper, memberMapper, orderItemMapper, productMapper, skuMapper));
    }

    private PmsProductPriceRule rule(Long id, String type, String level, String name,
                                     String price, Integer limit) {
        PmsProductPriceRule rule = new PmsProductPriceRule();
        rule.setId(id);
        rule.setProductId(10L);
        rule.setRuleType(type);
        rule.setMemberLevel(level);
        rule.setRuleName(name);
        rule.setPrice(new BigDecimal(price));
        rule.setPerMemberLimit(limit);
        rule.setPriority(0);
        rule.setStatus(1);
        rule.setStartTime(LocalDateTime.now().minusDays(1));
        rule.setEndTime(LocalDateTime.now().plusDays(1));
        return rule;
    }

    private record Fixture(PmsProductPriceRuleMapper ruleMapper,
                           UmsMemberMapper memberMapper,
                           OmsOrderItemMapper orderItemMapper,
                           ProductPriceRuleService service) {
    }
}
