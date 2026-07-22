package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("oms_order_return_apply")
public class OmsOrderReturnApply {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private String orderSn;
    private Long memberId;
    private Integer status;
    private String type;
    private String reason;
    private String description;
    private BigDecimal returnAmount;
    private String handleNote;
    private String returnCarrier;
    private String returnTrackingNo;
    private String inspectionResult;
    private String inspectionNote;
    private LocalDateTime slaDeadline;
    private Integer slaOverdue;
    private LocalDateTime receivedTime;
    private LocalDateTime handleTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
