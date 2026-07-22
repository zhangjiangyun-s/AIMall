package com.aimall.server.service;

import com.aimall.server.entity.KnowledgeIndexTask;
import com.aimall.server.mapper.KnowledgeIndexTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class KnowledgeTaskExecutionGuard {

    private static final Set<String> WRITABLE_STATUSES = Set.of("DISPATCHING", "RUNNING");

    private final KnowledgeIndexTaskMapper taskMapper;

    public KnowledgeTaskExecutionGuard(KnowledgeIndexTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    public KnowledgeIndexTask requireActive(String taskId, String executionToken) {
        KnowledgeIndexTask task = taskMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeIndexTask>()
                        .eq(KnowledgeIndexTask::getTaskId, taskId)
                        .last("LIMIT 1")
        );
        return validate(task, executionToken);
    }

    public KnowledgeIndexTask lockActive(String taskId, String executionToken) {
        return validate(taskMapper.selectByTaskIdForUpdate(taskId), executionToken);
    }

    private KnowledgeIndexTask validate(KnowledgeIndexTask task, String executionToken) {
        if (executionToken == null || executionToken.isBlank()) {
            throw new IllegalArgumentException("知识任务缺少 executionToken");
        }
        if (task == null) {
            throw new RuntimeException("知识库任务不存在");
        }
        if (!executionToken.equals(task.getExecutionToken()) || !WRITABLE_STATUSES.contains(task.getStatus())) {
            throw new IllegalStateException("知识任务执行代次已失效，拒绝旧执行写回");
        }
        return task;
    }
}
