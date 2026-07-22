package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("inventory_ledger")
public class InventoryLedger {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private Long productId;
    private Long skuId;
    private Long orderId;
    private Long orderItemId;
    private String operation;
    private Integer quantity;
    private Integer onHandDelta;
    private Integer reservedDelta;
    private Integer soldDelta;
    private Integer availableDelta;
    private Integer beforeAvailable;
    private Integer afterAvailable;
    private LocalDateTime createTime;
}
