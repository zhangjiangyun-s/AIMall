package com.aimall.server.service.impl;

import com.aimall.server.entity.KnowledgeDocAuditLog;
import com.aimall.server.mapper.KnowledgeDocAuditLogMapper;
import com.aimall.server.service.KnowledgeDocAuditLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import cn.dev33.satoken.stp.StpUtil;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class KnowledgeDocAuditLogServiceImpl implements KnowledgeDocAuditLogService {

    private final KnowledgeDocAuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    public KnowledgeDocAuditLogServiceImpl(KnowledgeDocAuditLogMapper auditLogMapper, ObjectMapper objectMapper) {
        this.auditLogMapper = auditLogMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public KnowledgeDocAuditLog record(Long docId, Long chunkId, String action, Object beforeSnapshot, Object afterSnapshot, String reason) {
        KnowledgeDocAuditLog log = new KnowledgeDocAuditLog();
        log.setDocId(docId);
        log.setChunkId(chunkId);
        log.setAction(action);
        applyOperator(log);
        log.setBeforeSnapshotJson(toJson(beforeSnapshot));
        log.setAfterSnapshotJson(toJson(afterSnapshot));
        log.setReason(reason);
        log.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
        return log;
    }

    private void applyOperator(KnowledgeDocAuditLog log) {
        if (!StpUtil.isLogin()) {
            log.setOperatorRole("SYSTEM");
            return;
        }
        String loginId = StpUtil.getLoginIdAsString();
        if (loginId != null && loginId.startsWith("admin_")) {
            log.setOperatorRole("ADMIN");
            try {
                log.setOperatorId(Long.parseLong(loginId.substring("admin_".length())));
            } catch (NumberFormatException ignored) {
                log.setOperatorId(null);
            }
            return;
        }
        log.setOperatorRole("USER");
    }

    @Override
    public List<KnowledgeDocAuditLog> listByDocId(Long docId) {
        return auditLogMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocAuditLog>()
                        .eq(KnowledgeDocAuditLog::getDocId, docId)
                        .orderByDesc(KnowledgeDocAuditLog::getId)
        );
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new RuntimeException("Failed to serialize knowledge audit snapshot", exception);
        }
    }
}
