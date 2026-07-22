package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("payment_reconciliation_item")
public class PaymentReconciliationItem {
    @TableId(type = IdType.AUTO) private Long id;
    private Long batchId;
    private Long orderId;
    private Long refundRecordId;
    private String differenceType;
    private String localStatus;
    private String providerStatus;
    private BigDecimal localAmount;
    private BigDecimal providerAmount;
    private String detail;
    private String resolutionStatus;
    private String autoQueryStatus;
    private Long claimedBy;
    private LocalDateTime claimedAt;
    private String resolutionNote;
    private Long resolvedBy;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private String approvalNo;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
}
