package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.math.BigDecimal;

@Data
@TableName("ums_member_coupon")
public class UmsMemberCoupon {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long memberId;
    private Long couponId;
    private String couponName;
    private Integer claimNo;
    private Integer status;
    private LocalDateTime usedTime;
    private Long orderId;
    private String orderSn;
    private BigDecimal actualDiscountAmount;
    private String usageState;
    private Long lockedOrderId;
    private LocalDateTime lockedAt;
    private LocalDateTime consumedAt;
    private LocalDateTime releasedAt;
    private LocalDateTime createTime;
}
