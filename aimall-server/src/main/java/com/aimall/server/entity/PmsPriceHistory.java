package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("pms_price_history")
public class PmsPriceHistory {
    @TableId(type = IdType.AUTO) private Long id;
    private String targetType;
    private Long productId;
    private Long skuId;
    private String priceType;
    private BigDecimal oldAmount;
    private BigDecimal newAmount;
    private Long operatorId;
    private String changeReason;
    private LocalDateTime createTime;
}
