package com.aimall.server.service.impl;

import com.aimall.server.entity.KnowledgeTaskEvent;
import com.aimall.server.mapper.KnowledgeTaskEventMapper;
import com.aimall.server.service.KnowledgeTaskEventService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class KnowledgeTaskEventServiceImpl implements KnowledgeTaskEventService {

    private final KnowledgeTaskEventMapper eventMapper;

    public KnowledgeTaskEventServiceImpl(KnowledgeTaskEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    @Override
    public KnowledgeTaskEvent record(
            String taskId,
            String eventType,
            String title,
            String detail,
            Integer progressCurrent,
            Integer progressTotal,
            boolean ok,
            String errorCode,
            String suggestion
    ) {
        KnowledgeTaskEvent event = new KnowledgeTaskEvent();
        event.setTaskId(taskId);
        event.setEventType(eventType);
        event.setTitle(title);
        event.setDetail(detail);
        event.setProgressCurrent(progressCurrent);
        event.setProgressTotal(progressTotal);
        event.setOk(ok);
        event.setErrorCode(errorCode);
        event.setSuggestion(suggestion);
        event.setCreatedAt(LocalDateTime.now());
        eventMapper.insert(event);
        return event;
    }

    @Override
    public List<KnowledgeTaskEvent> listByTaskId(String taskId) {
        return eventMapper.selectList(
                new LambdaQueryWrapper<KnowledgeTaskEvent>()
                        .eq(KnowledgeTaskEvent::getTaskId, taskId)
                        .orderByAsc(KnowledgeTaskEvent::getId)
        );
    }
}
