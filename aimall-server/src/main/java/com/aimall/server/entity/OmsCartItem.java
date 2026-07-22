package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("oms_cart_item")
public class OmsCartItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long productId;
    private Long productSkuId;
    private Long memberId;
    private Integer quantity;
    private BigDecimal price;
    private String productPic;
    private String productName;
    private String productSubTitle;
    private String productSkuCode;
    private String memberNickname;
    private LocalDateTime createDate;
    private LocalDateTime modifyDate;
    private Integer deleteStatus;
    private String productCategoryName;
    private String productBrand;
    private String productSn;
    private String productAttr;
}
