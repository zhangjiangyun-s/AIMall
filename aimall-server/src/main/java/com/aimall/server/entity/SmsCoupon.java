package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("sms_coupon")
public class SmsCoupon {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String type;
    private BigDecimal amount;
    private BigDecimal minPoint;
    private String platform;
    private String note;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer status;
    private Integer totalQuantity;
    private Integer remainingQuantity;
    private Integer perMemberLimit;
    private LocalDateTime receiveStartTime;
    private LocalDateTime receiveEndTime;
    private String scopeType;
    private String scopeIds;
    private BigDecimal budgetAmount;
    private BigDecimal usedBudget;
    private String refundPolicy;
}
