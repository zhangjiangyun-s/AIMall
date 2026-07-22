package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("knowledge_doc")
public class KnowledgeDoc {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String title;
    private String sourceType;
    private String content;
    private String status;
    private Integer version;
    private Long currentVersionId;
    private String sourceSystem;
    private BigDecimal sourceTrustScore;
    private String sourceUri;
    private String sourceHash;
    private String externalDocId;
    private String visibilityScope;
    private String roleScope;
    private String categoryIds;
    private String activityId;
    private String tags;
    private Long ownerUserId;
    private LocalDateTime effectiveTime;
    private LocalDateTime expireTime;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
