package com.aimall.server.service.impl;

import com.aimall.server.entity.KnowledgeIndexTask;
import com.aimall.server.service.KnowledgeIndexTaskService;
import com.aimall.server.service.KnowledgeTaskDispatcher;
import com.aimall.server.service.KnowledgeTaskEventService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class KnowledgeTaskDispatcherImpl implements KnowledgeTaskDispatcher {

    private final KnowledgeIndexTaskService taskService;
    private final KnowledgeTaskEventService eventService;
    private final RestTemplate restTemplate;
    private final String aiServiceBaseUrl;

    public KnowledgeTaskDispatcherImpl(
            KnowledgeIndexTaskService taskService,
            KnowledgeTaskEventService eventService,
            RestTemplate restTemplate,
            @Value("${ai-service.base-url:http://localhost:8000}") String aiServiceBaseUrl
    ) {
        this.taskService = taskService;
        this.eventService = eventService;
        this.restTemplate = restTemplate;
        this.aiServiceBaseUrl = aiServiceBaseUrl.replaceAll("/+$", "");
    }

    @Override
    public void dispatchAfterCommit(String taskId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatch(taskId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatch(taskId);
            }
        });
    }

    @Override
    public void dispatch(String taskId) {
        KnowledgeIndexTask task = taskService.claimPendingByBusinessTaskId(taskId);
        if (task != null) {
            dispatchClaimed(task);
        }
    }

    @Override
    public int dispatchPending(int limit) {
        var tasks = taskService.claimPendingTasks(limit);
        tasks.forEach(this::dispatchClaimed);
        return tasks.size();
    }

    private void dispatchClaimed(KnowledgeIndexTask task) {
        if (!"PROCESS_DOC_UPLOAD".equals(task.getTaskType())) {
            taskService.markFailed(task.getId(), "UNSUPPORTED_TASK", "当前任务类型尚未配置执行器");
            return;
        }
        eventService.record(
                task.getTaskId(), "task_dispatched", "投递文档处理任务",
                "任务已由调度器认领并发送到 ai-service",
                task.getProgressCurrent(), task.getProgressTotal(), true, null, null
        );
        CompletableFuture.runAsync(() -> {
            try {
                restTemplate.postForObject(
                        aiServiceBaseUrl + "/ai/knowledge/tasks/" + task.getTaskId() + "/process",
                        Map.of(
                                "executionToken", task.getExecutionToken(),
                                "attemptNo", task.getAttemptNo()
                        ), Map.class
                );
            } catch (ResourceAccessException exception) {
                if (!taskService.isCurrentExecution(task.getId(), task.getExecutionToken())) {
                    return;
                }
                eventService.record(
                        task.getTaskId(), "dispatch_uncertain", "AI 响应状态不确定", exception.getMessage(),
                        task.getProgressCurrent(), task.getProgressTotal(), false,
                        "AI_RESPONSE_UNCERTAIN", "不要立即判定失败；等待当前执行心跳或租约恢复"
                );
            } catch (Exception exception) {
                if (taskService.markFailedExecution(
                        task.getId(), task.getExecutionToken(), "AI_SERVICE_TRIGGER_FAILED", exception.getMessage()
                )) {
                eventService.record(
                        task.getTaskId(), "failed", "投递 ai-service 失败", exception.getMessage(),
                        task.getProgressCurrent(), task.getProgressTotal(), false,
                        "AI_SERVICE_TRIGGER_FAILED", "请确认 aimall-ai-service 已启动并监听配置端口"
                );
                }
            }
        });
    }
}
