package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("oms_order_return_item")
public class OmsOrderReturnItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long returnApplyId;
    private Long orderId;
    private Long orderItemId;
    private Long productId;
    private Long productSkuId;
    private Integer quantity;
    private BigDecimal refundAmount;
    private LocalDateTime createTime;
}
