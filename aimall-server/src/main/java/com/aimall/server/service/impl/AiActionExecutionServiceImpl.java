package com.aimall.server.service.impl;

import com.aimall.server.entity.AiActionExecution;
import com.aimall.server.mapper.AiActionExecutionMapper;
import com.aimall.server.service.AiActionExecutionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.List;

@Service
public class AiActionExecutionServiceImpl implements AiActionExecutionService {

    private final AiActionExecutionMapper executionMapper;
    private final ObjectMapper objectMapper;

    public AiActionExecutionServiceImpl(AiActionExecutionMapper executionMapper, ObjectMapper objectMapper) {
        this.executionMapper = executionMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Reservation reserve(String actionId, String actionType, Long memberId, Map<String, Object> request) {
        String requestHash = requestHash(request);
        AiActionExecution existing = findByActionId(actionId);
        if (existing != null) {
            return existingReservation(existing, actionType, memberId, requestHash);
        }

        LocalDateTime now = LocalDateTime.now();
        AiActionExecution execution = new AiActionExecution();
        execution.setActionId(actionId);
        execution.setActionType(actionType);
        execution.setMemberId(memberId);
        execution.setRequestHash(requestHash);
        execution.setStatus("PROCESSING");
        execution.setCreatedAt(now);
        execution.setUpdatedAt(now);
        try {
            executionMapper.insert(execution);
            return new Reservation(true, Map.of());
        } catch (DuplicateKeyException exception) {
            AiActionExecution raced = findByActionId(actionId);
            if (raced == null) {
                throw new RuntimeException("确认操作幂等记录创建失败");
            }
            return existingReservation(raced, actionType, memberId, requestHash);
        }
    }

    @Override
    public void markSuccess(String actionId, Map<String, Object> result) {
        LocalDateTime now = LocalDateTime.now();
        int updated = executionMapper.update(
                null,
                new LambdaUpdateWrapper<AiActionExecution>()
                        .eq(AiActionExecution::getActionId, actionId)
                        .eq(AiActionExecution::getStatus, "PROCESSING")
                        .set(AiActionExecution::getStatus, "SUCCEEDED")
                        .set(AiActionExecution::getResultJson, writeJson(result))
                        .set(AiActionExecution::getErrorMessage, null)
                        .set(AiActionExecution::getFinishedAt, now)
                        .set(AiActionExecution::getUpdatedAt, now)
        );
        if (updated != 1) {
            throw new RuntimeException("确认操作成功状态写入失败");
        }
    }

    @Override
    public void markFailed(String actionId, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        String message = errorMessage == null ? "未知错误" : errorMessage.substring(0, Math.min(1000, errorMessage.length()));
        executionMapper.update(
                null,
                new LambdaUpdateWrapper<AiActionExecution>()
                        .eq(AiActionExecution::getActionId, actionId)
                        .eq(AiActionExecution::getStatus, "PROCESSING")
                        .set(AiActionExecution::getStatus, "FAILED")
                        .set(AiActionExecution::getErrorMessage, message)
                        .set(AiActionExecution::getFinishedAt, now)
                        .set(AiActionExecution::getUpdatedAt, now)
        );
    }

    @Override
    public int markStaleExecutionsForRecovery() {
        return executionMapper.markStaleForRecovery();
    }

    @Override
    public List<AiActionExecution> listRecoveryRequired(int limit) {
        return executionMapper.selectList(
                new LambdaQueryWrapper<AiActionExecution>()
                        .eq(AiActionExecution::getStatus, "RECOVERY_REQUIRED")
                        .orderByAsc(AiActionExecution::getUpdatedAt)
                        .last("LIMIT " + Math.max(1, Math.min(limit, 100)))
        );
    }

    @Override
    public void resolveRecovery(
            String actionId,
            boolean succeeded,
            Map<String, Object> result,
            String note
    ) {
        LocalDateTime now = LocalDateTime.now();
        String safeNote = note == null || note.isBlank()
                ? "管理员完成人工核账"
                : note.substring(0, Math.min(1000, note.length()));
        LambdaUpdateWrapper<AiActionExecution> update = new LambdaUpdateWrapper<AiActionExecution>()
                .eq(AiActionExecution::getActionId, actionId)
                .eq(AiActionExecution::getStatus, "RECOVERY_REQUIRED")
                .set(AiActionExecution::getStatus, succeeded ? "SUCCEEDED" : "FAILED")
                .set(AiActionExecution::getErrorMessage, succeeded ? null : safeNote)
                .set(AiActionExecution::getFinishedAt, now)
                .set(AiActionExecution::getUpdatedAt, now);
        if (succeeded) {
            update.set(AiActionExecution::getResultJson, writeJson(result == null ? Map.of("recovered", true) : result));
        }
        if (executionMapper.update(null, update) != 1) {
            throw new RuntimeException("待恢复操作不存在或状态已变化");
        }
    }

    private Reservation existingReservation(
            AiActionExecution existing,
            String actionType,
            Long memberId,
            String requestHash
    ) {
        if (!actionType.equals(existing.getActionType())
                || !memberId.equals(existing.getMemberId())
                || !requestHash.equals(existing.getRequestHash())) {
            throw new RuntimeException("actionId 已被其他用户、参数或操作类型占用");
        }
        if ("SUCCEEDED".equals(existing.getStatus())) {
            Map<String, Object> result = readJson(existing.getResultJson());
            result.put("replayed", true);
            return new Reservation(false, result);
        }
        if ("PROCESSING".equals(existing.getStatus())) {
            throw new RuntimeException("确认操作正在执行，请勿重复提交");
        }
        if ("RECOVERY_REQUIRED".equals(existing.getStatus())) {
            throw new RuntimeException("确认操作结果待人工核对，请联系管理员");
        }
        throw new RuntimeException("确认操作此前执行失败，请重新发起新的操作");
    }

    private AiActionExecution findByActionId(String actionId) {
        return executionMapper.selectOne(
                new LambdaQueryWrapper<AiActionExecution>()
                        .eq(AiActionExecution::getActionId, actionId)
                        .last("LIMIT 1")
        );
    }

    private String requestHash(Map<String, Object> request) {
        try {
            String canonical = objectMapper.writeValueAsString(new TreeMap<>(request));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new RuntimeException("确认操作请求哈希计算失败", exception);
        }
    }

    private String writeJson(Map<String, Object> result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception exception) {
            throw new RuntimeException("确认操作结果序列化失败", exception);
        }
    }

    private Map<String, Object> readJson(String value) {
        if (value == null || value.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (Exception exception) {
            throw new RuntimeException("确认操作历史结果读取失败", exception);
        }
    }
}
