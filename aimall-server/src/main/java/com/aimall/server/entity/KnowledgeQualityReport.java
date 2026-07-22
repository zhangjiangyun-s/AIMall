package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("knowledge_quality_report")
public class KnowledgeQualityReport {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long docId;
    private Long docVersionId;
    private BigDecimal parseScore;
    private BigDecimal chunkScore;
    private BigDecimal piiScore;
    private BigDecimal promptRiskScore;
    private BigDecimal retrievalScore;
    private BigDecimal syncScore;
    private BigDecimal totalScore;
    private String grade;
    private String detail;
    private LocalDateTime createdAt;
}
