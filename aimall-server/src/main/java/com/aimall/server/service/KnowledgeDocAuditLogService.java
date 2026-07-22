package com.aimall.server.service;

import com.aimall.server.entity.KnowledgeDocAuditLog;

import java.util.List;

public interface KnowledgeDocAuditLogService {
    KnowledgeDocAuditLog record(Long docId, Long chunkId, String action, Object beforeSnapshot, Object afterSnapshot, String reason);

    List<KnowledgeDocAuditLog> listByDocId(Long docId);
}
