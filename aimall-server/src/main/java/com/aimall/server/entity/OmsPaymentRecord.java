package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("oms_payment_record")
public class OmsPaymentRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private String orderSn;
    private String payChannel;
    private String payStatus;
    private String paymentState;
    private BigDecimal amount;
    private BigDecimal paidAmount;
    private BigDecimal refundedAmount;
    private String transactionNo;
    private String providerReference;
    private String currencyCode;
    private Integer currencyScale;
    private LocalDateTime payTime;
    private LocalDateTime callbackTime;
    private String rawCallback;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
