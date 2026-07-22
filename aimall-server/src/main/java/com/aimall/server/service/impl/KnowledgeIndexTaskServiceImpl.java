package com.aimall.server.service.impl;

import com.aimall.server.entity.KnowledgeIndexTask;
import com.aimall.server.mapper.KnowledgeIndexTaskMapper;
import com.aimall.server.service.KnowledgeIndexTaskService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;

@Service
public class KnowledgeIndexTaskServiceImpl implements KnowledgeIndexTaskService {

    private static final List<String> ACTIVE_STATUSES = List.of("PENDING", "DISPATCHING", "RUNNING", "RETRY_WAIT");

    private final KnowledgeIndexTaskMapper taskMapper;

    public KnowledgeIndexTaskServiceImpl(KnowledgeIndexTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    @Override
    public KnowledgeIndexTask createRebuildDocTask(Long docId, String triggerType) {
        KnowledgeIndexTask activeTask = findActiveDocTask(docId, "REBUILD_DOC");
        if (activeTask != null) {
            return activeTask;
        }
        KnowledgeIndexTask task = baseTask("REBUILD_DOC", triggerType);
        task.setDocId(docId);
        task.setShardKey("doc:" + docId);
        taskMapper.insert(task);
        return task;
    }

    @Override
    public KnowledgeIndexTask createRebuildChunkTask(Long chunkId, Long docId, String triggerType) {
        KnowledgeIndexTask activeTask = findActiveChunkTask(chunkId, "REBUILD_CHUNK");
        if (activeTask != null) {
            return activeTask;
        }
        KnowledgeIndexTask task = baseTask("REBUILD_CHUNK", triggerType);
        task.setChunkId(chunkId);
        task.setDocId(docId);
        task.setShardKey("chunk:" + chunkId);
        taskMapper.insert(task);
        return task;
    }

    @Override
    public KnowledgeIndexTask createRebuildAllTask(String triggerType) {
        KnowledgeIndexTask task = baseTask("REBUILD_ALL", triggerType);
        task.setBatchId(UUID.randomUUID().toString());
        task.setQueueName("BACKFILL");
        task.setShardKey("all:" + task.getBatchId());
        taskMapper.insert(task);
        return task;
    }

    @Override
    public KnowledgeIndexTask createUploadDocTask(Long docId, Long docVersionId, String triggerType) {
        KnowledgeIndexTask activeTask = findActiveDocVersionTask(docVersionId, "PROCESS_DOC_UPLOAD");
        if (activeTask != null) {
            return activeTask;
        }
        KnowledgeIndexTask task = baseTask("PROCESS_DOC_UPLOAD", triggerType);
        task.setDocId(docId);
        task.setDocVersionId(docVersionId);
        task.setShardKey("doc-version:" + docVersionId);
        task.setCurrentStep("upload_received");
        task.setProgressCurrent(1);
        task.setProgressTotal(10);
        task.setTimeoutAt(LocalDateTime.now().plusMinutes(30));
        try {
            taskMapper.insert(task);
            return task;
        } catch (DuplicateKeyException exception) {
            KnowledgeIndexTask concurrentTask = findActiveDocVersionTask(docVersionId, "PROCESS_DOC_UPLOAD");
            if (concurrentTask != null) {
                return concurrentTask;
            }
            throw exception;
        }
    }

    @Override
    public KnowledgeIndexTask markRunning(Long taskId, String workerId) {
        KnowledgeIndexTask task = requireTask(taskId);
        task.setStatus("RUNNING");
        task.setLockedBy(workerId);
        task.setLockUntil(LocalDateTime.now().plusMinutes(10));
        task.setStartedAt(LocalDateTime.now());
        task.setLastHeartbeatAt(LocalDateTime.now());
        task.setTimeoutAt(LocalDateTime.now().plusMinutes(30));
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        return task;
    }

    @Override
    public KnowledgeIndexTask markSuccess(Long taskId) {
        KnowledgeIndexTask task = requireTask(taskId);
        task.setStatus("SUCCESS");
        task.setFinishedAt(LocalDateTime.now());
        task.setLockedBy(null);
        task.setLockUntil(null);
        task.setNextRetryAt(null);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        return task;
    }

    @Override
    public KnowledgeIndexTask markFailed(Long taskId, String errorCode, String errorMessage) {
        KnowledgeIndexTask task = requireTask(taskId);
        task.setStatus("FAILED");
        task.setErrorCode(errorCode);
        task.setErrorMessage(errorMessage);
        task.setFinishedAt(LocalDateTime.now());
        task.setLockedBy(null);
        task.setLockUntil(null);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        return task;
    }

    @Override
    public boolean markFailedExecution(Long taskId, String executionToken, String errorCode, String errorMessage) {
        if (executionToken == null || executionToken.isBlank()) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        KnowledgeIndexTask patch = new KnowledgeIndexTask();
        patch.setStatus("FAILED");
        patch.setErrorCode(errorCode);
        patch.setErrorMessage(errorMessage);
        patch.setFinishedAt(now);
        patch.setLockedBy(null);
        patch.setLockUntil(null);
        patch.setUpdatedAt(now);
        return taskMapper.update(
                patch,
                new LambdaQueryWrapper<KnowledgeIndexTask>()
                        .eq(KnowledgeIndexTask::getId, taskId)
                        .eq(KnowledgeIndexTask::getExecutionToken, executionToken)
                        .in(KnowledgeIndexTask::getStatus, "DISPATCHING", "RUNNING")
        ) == 1;
    }

    @Override
    public boolean isCurrentExecution(Long taskId, String executionToken) {
        if (executionToken == null || executionToken.isBlank()) {
            return false;
        }
        return taskMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeIndexTask>()
                        .eq(KnowledgeIndexTask::getId, taskId)
                        .eq(KnowledgeIndexTask::getExecutionToken, executionToken)
                        .in(KnowledgeIndexTask::getStatus, "DISPATCHING", "RUNNING")
        ) == 1;
    }

    @Override
    public KnowledgeIndexTask retry(Long taskId) {
        KnowledgeIndexTask task = requireTask(taskId);
        if (!("FAILED".equals(task.getStatus()) || "PARTIAL_FAILED".equals(task.getStatus()) || "DEAD_LETTER".equals(task.getStatus()))) {
            throw new RuntimeException("只有失败或死信任务可以人工重试");
        }
        int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        int maxRetry = task.getMaxRetry() == null ? 3 : task.getMaxRetry();
        if ("DEAD_LETTER".equals(task.getStatus())) {
            retryCount = 0;
        } else if (retryCount >= maxRetry) {
            task.setStatus("DEAD_LETTER");
            task.setDeadLetterReason("自动重试次数已达到上限：" + maxRetry);
            task.setFinishedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            throw new RuntimeException("任务已达到重试上限并进入死信队列");
        }
        if ("REBUILD_DOC".equals(task.getTaskType()) && findActiveDocTask(task.getDocId(), "REBUILD_DOC") != null) {
            throw new RuntimeException("A rebuild task is already active for this document");
        }
        if ("REBUILD_CHUNK".equals(task.getTaskType()) && findActiveChunkTask(task.getChunkId(), "REBUILD_CHUNK") != null) {
            throw new RuntimeException("A rebuild task is already active for this chunk");
        }
        if ("PROCESS_DOC_UPLOAD".equals(task.getTaskType())
                && findActiveDocVersionTask(task.getDocVersionId(), "PROCESS_DOC_UPLOAD") != null) {
            throw new RuntimeException("该文档版本已有活动处理任务，不能并发重试");
        }
        task.setStatus("PENDING");
        task.setRetryCount(retryCount + 1);
        task.setTriggerType("MANUAL_RETRY");
        task.setErrorCode(null);
        task.setErrorMessage(null);
        task.setDeadLetterReason(null);
        task.setNextRetryAt(null);
        task.setLockedBy(null);
        task.setLockUntil(null);
        task.setExecutionToken(null);
        task.setTimeoutAt(LocalDateTime.now().plusMinutes(30));
        task.setStartedAt(null);
        task.setFinishedAt(null);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        return task;
    }

    @Override
    public List<KnowledgeIndexTask> recoverStalledTasks(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime failedRetryCutoff = now.minusSeconds(15);
        List<KnowledgeIndexTask> candidates = taskMapper.selectList(
                new LambdaQueryWrapper<KnowledgeIndexTask>()
                        .in(KnowledgeIndexTask::getStatus, "PENDING", "DISPATCHING", "RUNNING", "FAILED", "PARTIAL_FAILED")
                        .eq(KnowledgeIndexTask::getTaskType, "PROCESS_DOC_UPLOAD")
                        .orderByAsc(KnowledgeIndexTask::getPriority)
                        .orderByAsc(KnowledgeIndexTask::getCreatedAt)
                        .last("LIMIT " + safeLimit)
        );
        return candidates.stream()
                .filter(task -> isRecoverable(task, now, failedRetryCutoff))
                .map(task -> recoverTask(task, now))
                .filter(task -> task != null)
                .toList();
    }

    @Override
    public List<KnowledgeIndexTask> dispatchReadyRetries(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        LocalDateTime now = LocalDateTime.now();
        List<KnowledgeIndexTask> candidates = taskMapper.selectList(
                new LambdaQueryWrapper<KnowledgeIndexTask>()
                        .eq(KnowledgeIndexTask::getStatus, "RETRY_WAIT")
                        .le(KnowledgeIndexTask::getNextRetryAt, now)
                        .orderByAsc(KnowledgeIndexTask::getPriority)
                        .orderByAsc(KnowledgeIndexTask::getNextRetryAt)
                        .last("LIMIT " + safeLimit)
        );
        return candidates.stream().map(task -> {
            task.setStatus("PENDING");
            task.setCurrentStep("retry_dispatching");
            task.setNextRetryAt(null);
            task.setTimeoutAt(now.plusMinutes(30));
            task.setUpdatedAt(now);
            int updated = taskMapper.update(
                    task,
                    new LambdaQueryWrapper<KnowledgeIndexTask>()
                            .eq(KnowledgeIndexTask::getId, task.getId())
                            .eq(KnowledgeIndexTask::getStatus, "RETRY_WAIT")
            );
            return updated == 1 ? task : null;
        }).filter(task -> task != null).toList();
    }

    @Override
    public KnowledgeIndexTask claimPendingByBusinessTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        return claimPending(getByBusinessTaskId(taskId));
    }

    @Override
    public List<KnowledgeIndexTask> claimPendingTasks(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<KnowledgeIndexTask> candidates = taskMapper.selectList(
                new LambdaQueryWrapper<KnowledgeIndexTask>()
                        .eq(KnowledgeIndexTask::getTaskType, "PROCESS_DOC_UPLOAD")
                        .eq(KnowledgeIndexTask::getStatus, "PENDING")
                        .orderByAsc(KnowledgeIndexTask::getPriority)
                        .orderByAsc(KnowledgeIndexTask::getCreatedAt)
                        .last("LIMIT " + safeLimit)
        );
        return candidates.stream()
                .map(this::claimPending)
                .filter(task -> task != null)
                .toList();
    }

    @Override
    public KnowledgeIndexTask getByBusinessTaskId(String taskId) {
        return taskMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeIndexTask>()
                        .eq(KnowledgeIndexTask::getTaskId, taskId)
                        .last("LIMIT 1")
        );
    }

    @Override
    public List<KnowledgeIndexTask> listRecent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return taskMapper.selectList(
                new LambdaQueryWrapper<KnowledgeIndexTask>()
                        .orderByDesc(KnowledgeIndexTask::getId)
                        .last("LIMIT " + safeLimit)
        );
    }

    @Override
    public List<KnowledgeIndexTask> listRunningByDocVersionIds(List<Long> docVersionIds) {
        if (docVersionIds == null || docVersionIds.isEmpty()) {
            return List.of();
        }
        return taskMapper.selectList(
                new LambdaQueryWrapper<KnowledgeIndexTask>()
                        .in(KnowledgeIndexTask::getDocVersionId, docVersionIds)
                        .eq(KnowledgeIndexTask::getTaskType, "PROCESS_DOC_UPLOAD")
                        .eq(KnowledgeIndexTask::getStatus, "RUNNING")
                        .isNotNull(KnowledgeIndexTask::getExecutionToken)
        );
    }

    @Override
    public long countByStatus(String status) {
        return taskMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeIndexTask>()
                        .eq(KnowledgeIndexTask::getStatus, status)
        );
    }

    private KnowledgeIndexTask baseTask(String taskType, String triggerType) {
        LocalDateTime now = LocalDateTime.now();
        KnowledgeIndexTask task = new KnowledgeIndexTask();
        task.setTaskId(buildTaskId());
        task.setTaskType(taskType);
        String traceId = MDC.get("traceId");
        task.setTraceId(traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId);
        task.setStatus("PENDING");
        task.setTriggerType(triggerType == null ? "MANUAL" : triggerType);
        task.setQueueName("DEFAULT");
        task.setPriority(100);
        task.setRetryCount(0);
        task.setMaxRetry(3);
        task.setProgressCurrent(0);
        task.setProgressTotal(0);
        task.setAlertEnabled(true);
        task.setChunkStrategyVersion("chunk-v1");
        task.setIndexVersion("index-v1");
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task.setTimeoutAt(now.plusMinutes(30));
        return task;
    }

    private boolean isRecoverable(KnowledgeIndexTask task, LocalDateTime now, LocalDateTime failedRetryCutoff) {
        if ("FAILED".equals(task.getStatus()) || "PARTIAL_FAILED".equals(task.getStatus())) {
            return task.getUpdatedAt() == null || !task.getUpdatedAt().isAfter(failedRetryCutoff);
        }
        boolean deadlineExpired = task.getTimeoutAt() != null && !task.getTimeoutAt().isAfter(now);
        boolean leaseExpired = "RUNNING".equals(task.getStatus())
                && task.getLockUntil() != null
                && !task.getLockUntil().isAfter(now);
        return deadlineExpired || leaseExpired;
    }

    private KnowledgeIndexTask recoverTask(KnowledgeIndexTask task, LocalDateTime now) {
        String previousStatus = task.getStatus();
        int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        int maxRetry = task.getMaxRetry() == null ? 3 : task.getMaxRetry();
        if (retryCount >= maxRetry) {
            task.setStatus("DEAD_LETTER");
            task.setDeadLetterReason("任务在 " + previousStatus + " 状态下恢复失败，已达到最大重试次数 " + maxRetry);
            task.setErrorCode("RETRY_EXHAUSTED");
            task.setFinishedAt(now);
            task.setNextRetryAt(null);
        } else {
            int nextRetryCount = retryCount + 1;
            long backoffSeconds = Math.min(300L, 15L * (1L << Math.min(nextRetryCount - 1, 4)));
            task.setStatus("RETRY_WAIT");
            task.setRetryCount(nextRetryCount);
            task.setErrorCode("AUTO_RETRY");
            task.setErrorMessage("从 " + previousStatus + " 状态自动恢复，第 " + nextRetryCount + " 次重试");
            task.setCurrentStep("retry_wait");
            task.setStartedAt(null);
            task.setFinishedAt(null);
            task.setNextRetryAt(now.plusSeconds(backoffSeconds));
            task.setTimeoutAt(null);
        }
        task.setLockedBy(null);
        task.setLockUntil(null);
        task.setUpdatedAt(now);
        int updated = taskMapper.update(
                task,
                new LambdaQueryWrapper<KnowledgeIndexTask>()
                        .eq(KnowledgeIndexTask::getId, task.getId())
                        .eq(KnowledgeIndexTask::getStatus, previousStatus)
        );
        return updated == 1 ? task : null;
    }

    private KnowledgeIndexTask claimPending(KnowledgeIndexTask task) {
        if (task == null || !"PENDING".equals(task.getStatus())) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        task.setStatus("DISPATCHING");
        task.setExecutionToken(UUID.randomUUID().toString().replace("-", ""));
        task.setAttemptNo((task.getAttemptNo() == null ? 0 : task.getAttemptNo()) + 1);
        task.setCurrentStep("dispatching");
        task.setTimeoutAt(now.plusMinutes(2));
        task.setUpdatedAt(now);
        int updated = taskMapper.update(
                task,
                new LambdaQueryWrapper<KnowledgeIndexTask>()
                        .eq(KnowledgeIndexTask::getId, task.getId())
                        .eq(KnowledgeIndexTask::getStatus, "PENDING")
        );
        return updated == 1 ? task : null;
    }

    private String buildTaskId() {
        String date = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        return "KT" + date + suffix;
    }

    private KnowledgeIndexTask findActiveDocTask(Long docId, String taskType) {
        if (docId == null) return null;
        return taskMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeIndexTask>()
                        .eq(KnowledgeIndexTask::getDocId, docId)
                        .eq(KnowledgeIndexTask::getTaskType, taskType)
                        .in(KnowledgeIndexTask::getStatus, ACTIVE_STATUSES)
                        .last("LIMIT 1")
        );
    }

    private KnowledgeIndexTask findActiveChunkTask(Long chunkId, String taskType) {
        if (chunkId == null) return null;
        return taskMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeIndexTask>()
                        .eq(KnowledgeIndexTask::getChunkId, chunkId)
                        .eq(KnowledgeIndexTask::getTaskType, taskType)
                        .in(KnowledgeIndexTask::getStatus, ACTIVE_STATUSES)
                        .last("LIMIT 1")
        );
    }

    private KnowledgeIndexTask findActiveDocVersionTask(Long docVersionId, String taskType) {
        if (docVersionId == null) return null;
        return taskMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeIndexTask>()
                        .eq(KnowledgeIndexTask::getDocVersionId, docVersionId)
                        .eq(KnowledgeIndexTask::getTaskType, taskType)
                        .in(KnowledgeIndexTask::getStatus, ACTIVE_STATUSES)
                        .last("LIMIT 1")
        );
    }

    private KnowledgeIndexTask requireTask(Long taskId) {
        KnowledgeIndexTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new RuntimeException("Knowledge index task does not exist");
        }
        return task;
    }
}
