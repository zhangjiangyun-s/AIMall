package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("payment_reconciliation_batch")
public class PaymentReconciliationBatch {
    @TableId(type = IdType.AUTO) private Long id;
    private String batchNo;
    private String provider;
    private LocalDate reconcileDate;
    private String status;
    private Integer checkedCount;
    private Integer differenceCount;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
