package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("late_payment_case")
public class LatePaymentCase {
    @TableId(type = IdType.AUTO) private Long id;
    private Long paymentId;
    private Long orderId;
    private String orderSn;
    private BigDecimal amount;
    private String providerTradeNo;
    private String refundRequestId;
    private String refundTradeNo;
    private String status;
    private Integer retryCount;
    private Integer queryRetryCount;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime finishedTime;
}
