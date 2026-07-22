package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("knowledge_retrieval_test")
public class KnowledgeRetrievalTest {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long docId;
    private Long docVersionId;
    private String testQuery;
    private Long expectedDocId;
    private Long hitDocId;
    private Long hitChunkId;
    private BigDecimal topScore;
    private Boolean passed;
    private String detail;
    private LocalDateTime createdAt;
}
