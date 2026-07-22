package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oms_shipment")
public class OmsShipment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String shipmentSn;
    private Long orderId;
    private String orderSn;
    private String carrierCode;
    private String carrierName;
    private String trackingNo;
    private String status;
    private LocalDateTime shippedAt;
    private LocalDateTime deliveredAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
