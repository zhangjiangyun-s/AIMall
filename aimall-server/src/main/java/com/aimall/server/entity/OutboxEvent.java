package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("outbox_event")
public class OutboxEvent {
    @TableId(type = IdType.AUTO) private Long id;
    private String eventId;
    private String aggregateType;
    private String aggregateId;
    private Long aggregateVersion;
    private String eventType;
    private String idempotencyKey;
    private String payloadJson;
    private String payloadHash;
    private String traceId;
    private String tenantId;
    private LocalDateTime occurredAtUtc;
    private String producerVersion;
    private Integer payloadSchemaVersion;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextAttemptAt;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private String lastLeaseOwner;
    private String lastErrorCode;
    private String lastErrorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;
}
