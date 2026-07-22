package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("oms_order")
public class OmsOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long memberId;
    private String memberUsername;
    private String orderSn;
    private BigDecimal totalAmount;
    private BigDecimal payAmount;
    private BigDecimal freightAmount;
    private BigDecimal promotionAmount;
    private BigDecimal couponAmount;
    private BigDecimal discountAmount;
    private Integer payType;
    private Integer sourceType;
    private Integer status;
    private Integer orderType;
    private String deliveryCompany;
    private String deliverySn;
    private String promotionInfo;
    private String receiverName;
    private String receiverPhone;
    private String receiverProvince;
    private String receiverCity;
    private String receiverRegion;
    private String receiverDetailAddress;
    private String note;
    private Integer confirmStatus;
    private Integer deleteStatus;
    private String refundStatus;
    private BigDecimal refundedAmount;
    private LocalDateTime createTime;
    private LocalDateTime paymentTime;
    private LocalDateTime deliveryTime;
    private LocalDateTime receiveTime;
    private LocalDateTime modifyTime;
    private LocalDateTime expireTime;
    private String inventoryReservationStatus;
    private Integer financialHold;
    private String financialHoldReason;
    private String currencyCode;
    private Integer currencyScale;
}
