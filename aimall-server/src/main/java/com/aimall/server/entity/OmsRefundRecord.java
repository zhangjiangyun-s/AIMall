package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("oms_refund_record")
public class OmsRefundRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestId;
    private Long returnApplyId;
    private Long orderId;
    private String orderSn;
    private String refundChannel;
    private String refundStatus;
    private String refundState;
    private BigDecimal amount;
    private String refundTransactionNo;
    private String channelReference;
    private String failureReason;
    private String handleNote;
    private Integer retryCount;
    private Integer maxRetry;
    private Integer manualRetryCount;
    private Integer queryRetryCount;
    private Integer queryCount;
    private String providerStatus;
    private String reconciliationStatus;
    private Long manualOwner;
    private String currencyCode;
    private Integer currencyScale;
    private LocalDateTime lastQueryTime;
    private LocalDateTime lastQueryAt;
    private LocalDateTime nextRetryTime;
    private LocalDateTime channelCalledTime;
    private LocalDateTime finishedTime;
    private LocalDateTime closedTime;
    private String closedReason;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
