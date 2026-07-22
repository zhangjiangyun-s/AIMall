package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("payment_callback_event")
public class PaymentCallbackEvent {
    @TableId(type = IdType.AUTO) private Long id;
    private String eventId;
    private String provider;
    private String providerReference;
    private String requestId;
    private String traceId;
    private String tenantId;
    private String eventSource;
    private Long orderId;
    private String providerStatus;
    private java.math.BigDecimal amount;
    private Integer signatureValid;
    private String payloadHash;
    private String rawPayload;
    private String processingState;
    private LocalDateTime processedAt;
    private String failureCode;
    private LocalDateTime createTime;
}
