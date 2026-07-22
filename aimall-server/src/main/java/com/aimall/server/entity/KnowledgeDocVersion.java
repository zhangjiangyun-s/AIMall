package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("knowledge_doc_version")
public class KnowledgeDocVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long docId;
    private Integer versionNo;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String sourceHash;
    private String storagePath;
    private String parsedJsonPath;
    private String previewTextPath;
    private String status;
    private Integer pageCount;
    private Integer paragraphCount;
    private Integer tableCount;
    private Integer imageCount;
    private Integer piiCount;
    private String promptRiskLevel;
    private BigDecimal qualityScore;
    private String publicationVersion;
    private Long retrievalEpoch;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
