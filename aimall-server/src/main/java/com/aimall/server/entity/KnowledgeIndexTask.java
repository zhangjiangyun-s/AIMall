package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_index_task")
public class KnowledgeIndexTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskId;
    private String batchId;
    private Long docId;
    private Long docVersionId;
    private Long chunkId;
    private String taskType;
    private String traceId;
    private String status;
    private String executionToken;
    private Integer attemptNo;
    private String currentStep;
    private Integer progressCurrent;
    private Integer progressTotal;
    private String triggerType;
    private String queueName;
    private String shardKey;
    private Integer priority;
    private Integer retryCount;
    private Integer maxRetry;
    private Boolean alertEnabled;
    private String alertChannel;
    private String chunkStrategyVersion;
    private String embeddingModel;
    private String embeddingModelVersion;
    private String indexVersion;
    private String lockedBy;
    private LocalDateTime lockUntil;
    private LocalDateTime timeoutAt;
    private LocalDateTime nextRetryAt;
    private LocalDateTime lastHeartbeatAt;
    private String errorCode;
    private String errorMessage;
    private String deadLetterReason;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
