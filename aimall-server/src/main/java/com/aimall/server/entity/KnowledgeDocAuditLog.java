package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_doc_audit_log")
public class KnowledgeDocAuditLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long docId;
    private Long chunkId;
    private String action;
    private Long operatorId;
    private String operatorRole;
    private String beforeSnapshotJson;
    private String afterSnapshotJson;
    private String reason;
    private LocalDateTime createdAt;
}
