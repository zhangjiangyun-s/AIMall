package com.aimall.server.service.impl;

import com.aimall.server.entity.KnowledgeIndexTask;
import com.aimall.server.service.KnowledgeIndexTaskService;
import com.aimall.server.service.KnowledgeTaskDispatcher;
import com.aimall.server.service.KnowledgeTaskEventService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeTaskRecoveryService {

    private final KnowledgeIndexTaskService taskService;
    private final KnowledgeTaskEventService eventService;
    private final KnowledgeTaskDispatcher taskDispatcher;

    public KnowledgeTaskRecoveryService(
            KnowledgeIndexTaskService taskService,
            KnowledgeTaskEventService eventService,
            KnowledgeTaskDispatcher taskDispatcher
    ) {
        this.taskService = taskService;
        this.eventService = eventService;
        this.taskDispatcher = taskDispatcher;
    }

    @Scheduled(fixedDelayString = "${aimall.knowledge.recovery-scan-ms:30000}")
    public void scanAndRecover() {
        for (KnowledgeIndexTask task : taskService.recoverStalledTasks(50)) {
            if ("DEAD_LETTER".equals(task.getStatus())) {
                eventService.record(
                        task.getTaskId(),
                        "dead_letter",
                        "任务进入死信队列",
                        task.getDeadLetterReason(),
                        task.getProgressCurrent(),
                        task.getProgressTotal(),
                        false,
                        "RETRY_EXHAUSTED",
                        "请在 Admin 任务列表中检查原始错误后人工重试"
                );
            } else {
                eventService.record(
                        task.getTaskId(),
                        "retry_scheduled",
                        "任务等待自动重试",
                        "第 " + task.getRetryCount() + " 次重试，计划时间：" + task.getNextRetryAt(),
                        task.getProgressCurrent(),
                        task.getProgressTotal(),
                        true,
                        null,
                        null
                );
            }
        }
        for (KnowledgeIndexTask task : taskService.dispatchReadyRetries(50)) {
            eventService.record(
                    task.getTaskId(),
                    "retry_dispatched",
                    "重新投递任务",
                    "自动重试第 " + task.getRetryCount() + " 次",
                    task.getProgressCurrent(),
                    task.getProgressTotal(),
                    true,
                    null,
                    null
            );
            taskDispatcher.dispatch(task.getTaskId());
        }
        taskDispatcher.dispatchPending(50);
    }

    public KnowledgeIndexTask retryManually(Long taskId) {
        KnowledgeIndexTask task = taskService.retry(taskId);
        eventService.record(
                task.getTaskId(),
                "manual_retry",
                "管理员人工重试",
                "任务已重新投递，重试计数：" + task.getRetryCount(),
                task.getProgressCurrent(),
                task.getProgressTotal(),
                true,
                null,
                null
        );
        taskDispatcher.dispatch(task.getTaskId());
        return task;
    }
}
