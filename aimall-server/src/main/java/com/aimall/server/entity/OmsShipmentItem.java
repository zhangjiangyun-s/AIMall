package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("oms_shipment_item")
public class OmsShipmentItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shipmentId;
    private Long orderItemId;
    private Integer quantity;
}
