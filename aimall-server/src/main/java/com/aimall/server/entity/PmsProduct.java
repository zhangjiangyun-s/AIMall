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
@TableName("pms_product")
public class PmsProduct {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long brandId;
    private Long categoryId;
    private String name;
    private String pic;
    private String productSn;
    private Integer deleteStatus;
    private Integer publishStatus;
    private Integer newStatus;
    private Integer recommandStatus;
    private Integer verifyStatus;
    private Integer sort;
    private Integer sale;
    @TableField("price_v2")
    private BigDecimal price;
    @JsonIgnore
    @TableField("price")
    private BigDecimal legacyPrice;
    @TableField("promotion_price_v2")
    private BigDecimal promotionPrice;
    @JsonIgnore
    @TableField("promotion_price")
    private BigDecimal legacyPromotionPrice;
    private Integer giftPoint;
    private String subTitle;
    @TableField("original_price_v2")
    private BigDecimal originalPrice;
    @JsonIgnore
    @TableField("original_price")
    private BigDecimal legacyOriginalPrice;
    private Integer stock;
    private Integer lockStock;
    private Integer lowStock;
    private String unit;
    private BigDecimal weight;
    private String keywords;
    private String brandName;
    private String productCategoryName;
    private String description;
    private String detailDesc;
    private String detailHtml;
    private String detailMobileHtml;
    private LocalDateTime createTime;

    public void setPrice(BigDecimal value) {
        this.price = value == null ? null : MoneyPolicy.storage(value);
        this.legacyPrice = value == null ? null : MoneyPolicy.channel(value, 2);
    }

    public void setPromotionPrice(BigDecimal value) {
        this.promotionPrice = value == null ? null : MoneyPolicy.storage(value);
        this.legacyPromotionPrice = value == null ? null : MoneyPolicy.channel(value, 2);
    }

    public void setOriginalPrice(BigDecimal value) {
        this.originalPrice = value == null ? null : MoneyPolicy.storage(value);
        this.legacyOriginalPrice = value == null ? null : MoneyPolicy.channel(value, 2);
    }
}
