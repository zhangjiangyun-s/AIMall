package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("payment_correction_event")
public class PaymentCorrectionEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventNo;
    private Long reconciliationItemId;
    private String correctionType;
    private String reason;
    private String evidence;
    private String originalValueJson;
    private String proposedValueJson;
    private Long operatorId;
    private Long reviewerId;
    private String approvalNo;
    private String status;
    private String reviewNote;
    private LocalDateTime createdAt;
    private LocalDateTime reviewedAt;
}
