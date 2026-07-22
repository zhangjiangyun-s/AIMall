package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.aimall.server.money.MoneyPolicy;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("pms_sku_stock")
public class PmsSkuStock {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long productId;
    private String skuCode;
    @TableField("price_v2")
    private BigDecimal price;
    @JsonIgnore
    @TableField("price")
    private BigDecimal legacyPrice;
    private Integer stock;
    private Integer lowStock;
    private String pic;
    private Integer sale;
    @TableField("promotion_price_v2")
    private BigDecimal promotionPrice;
    @JsonIgnore
    @TableField("promotion_price")
    private BigDecimal legacyPromotionPrice;
    private Integer lockStock;
    private String spData;
    private Integer status;

    public void setPrice(BigDecimal value) {
        this.price = value == null ? null : MoneyPolicy.storage(value);
        this.legacyPrice = value == null ? null : MoneyPolicy.channel(value, 2);
    }

    public void setPromotionPrice(BigDecimal value) {
        this.promotionPrice = value == null ? null : MoneyPolicy.storage(value);
        this.legacyPromotionPrice = value == null ? null : MoneyPolicy.channel(value, 2);
    }
}
