package com.aimall.server.service;

import com.aimall.server.entity.KnowledgeTaskEvent;

import java.util.List;

public interface KnowledgeTaskEventService {
    KnowledgeTaskEvent record(
            String taskId,
            String eventType,
            String title,
            String detail,
            Integer progressCurrent,
            Integer progressTotal,
            boolean ok,
            String errorCode,
            String suggestion
    );

    List<KnowledgeTaskEvent> listByTaskId(String taskId);
}
