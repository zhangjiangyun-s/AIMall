package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_chunk")
public class KnowledgeChunk {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long docId;
    private Long docVersionId;
    private Integer docVersion;
    private String chunkKey;
    private String chunkType;
    private Integer chunkNo;
    private Long parentChunkId;
    private Long prevChunkId;
    private Long nextChunkId;
    private String title;
    private String sectionTitle;
    private String sectionPath;
    private String originalContent;
    private String maskedContent;
    private String indexContent;
    private String snippet;
    private Integer tokenCount;
    private String contentHash;
    private String chunkHash;
    private Integer pageStart;
    private Integer pageEnd;
    private String sourceType;
    private String sourceRef;
    private String status;
    private String visibilityScope;
    private String tenantId;
    private String roleScope;
    private String categoryIds;
    private String activityId;
    private LocalDateTime effectiveTime;
    private LocalDateTime expireTime;
    private String keywordIndexVersion;
    private String embeddingId;
    private String embeddingModel;
    private String embeddingModelVersion;
    private String embeddingSyncStatus;
    private Integer vectorDeleteRetryCount;
    private LocalDateTime vectorDeleteNextRetryAt;
    private String vectorDeleteClaimToken;
    private LocalDateTime vectorDeleteClaimUntil;
    private String vectorDeleteLastError;
    private String vectorCollection;
    private String indexVersion;
    private String publicationVersion;
    private Long retrievalEpoch;
    private String chunkStrategyVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
