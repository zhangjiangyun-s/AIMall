package com.aimall.server.service;

import com.aimall.server.entity.KnowledgeIndexTask;

import java.util.List;

public interface KnowledgeIndexTaskService {
    KnowledgeIndexTask createRebuildDocTask(Long docId, String triggerType);

    KnowledgeIndexTask createRebuildChunkTask(Long chunkId, Long docId, String triggerType);

    KnowledgeIndexTask createRebuildAllTask(String triggerType);

    KnowledgeIndexTask createUploadDocTask(Long docId, Long docVersionId, String triggerType);

    KnowledgeIndexTask markRunning(Long taskId, String workerId);

    KnowledgeIndexTask markSuccess(Long taskId);

    KnowledgeIndexTask markFailed(Long taskId, String errorCode, String errorMessage);

    boolean markFailedExecution(Long taskId, String executionToken, String errorCode, String errorMessage);

    boolean isCurrentExecution(Long taskId, String executionToken);

    KnowledgeIndexTask retry(Long taskId);

    List<KnowledgeIndexTask> recoverStalledTasks(int limit);

    List<KnowledgeIndexTask> dispatchReadyRetries(int limit);

    KnowledgeIndexTask claimPendingByBusinessTaskId(String taskId);

    List<KnowledgeIndexTask> claimPendingTasks(int limit);

    KnowledgeIndexTask getByBusinessTaskId(String taskId);

    List<KnowledgeIndexTask> listRecent(int limit);

    List<KnowledgeIndexTask> listRunningByDocVersionIds(List<Long> docVersionIds);

    long countByStatus(String status);
}
