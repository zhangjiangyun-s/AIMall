package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oms_order_request")
public class OmsOrderRequest {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long memberId;
    private String requestId;
    private String status;
    private Long orderId;
    private String orderSn;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
