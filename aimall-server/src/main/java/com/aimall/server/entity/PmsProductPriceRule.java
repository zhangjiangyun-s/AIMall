package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.aimall.server.money.MoneyPolicy;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("pms_product_price_rule")
public class PmsProductPriceRule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long productId;
    private Long skuId;
    private String ruleType;
    private String ruleName;
    private String memberLevel;
    @TableField("price_v2")
    private BigDecimal price;
    @JsonIgnore
    @TableField("price")
    private BigDecimal legacyPrice;
    private Integer perMemberLimit;
    private Integer priority;
    private Integer status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public void setPrice(BigDecimal value) {
        this.price = value == null ? null : MoneyPolicy.storage(value);
        this.legacyPrice = value == null ? null : MoneyPolicy.channel(value, 2);
    }
}
