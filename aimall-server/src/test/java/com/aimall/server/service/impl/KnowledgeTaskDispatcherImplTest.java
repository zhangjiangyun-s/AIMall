package com.aimall.server.service.impl;

import com.aimall.server.entity.KnowledgeIndexTask;
import com.aimall.server.service.KnowledgeIndexTaskService;
import com.aimall.server.service.KnowledgeTaskEventService;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;

class KnowledgeTaskDispatcherImplTest {

    @Test
    void dispatchClaimsPendingTaskBeforeCallingAiService() throws Exception {
        KnowledgeIndexTaskService taskService = mock(KnowledgeIndexTaskService.class);
        KnowledgeTaskEventService eventService = mock(KnowledgeTaskEventService.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        KnowledgeIndexTask task = task(1L, "KT-DISPATCH-1");
        when(taskService.claimPendingByBusinessTaskId(task.getTaskId())).thenReturn(task);
        CountDownLatch called = new CountDownLatch(1);
        when(restTemplate.postForObject(any(String.class), any(), eq(Map.class))).thenAnswer(invocation -> {
            called.countDown();
            return null;
        });
        KnowledgeTaskDispatcherImpl dispatcher = new KnowledgeTaskDispatcherImpl(
                taskService, eventService, restTemplate, "http://localhost:8000"
        );

        dispatcher.dispatch(task.getTaskId());

        assertTrue(called.await(2, TimeUnit.SECONDS));
        verify(taskService).claimPendingByBusinessTaskId(task.getTaskId());
    }

    @Test
    void dispatchPendingUsesOnlyAtomicallyClaimedTasks() throws Exception {
        KnowledgeIndexTaskService taskService = mock(KnowledgeIndexTaskService.class);
        KnowledgeTaskEventService eventService = mock(KnowledgeTaskEventService.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        KnowledgeIndexTask task = task(2L, "KT-DISPATCH-2");
        when(taskService.claimPendingTasks(10)).thenReturn(List.of(task));
        CountDownLatch called = new CountDownLatch(1);
        when(restTemplate.postForObject(any(String.class), any(), eq(Map.class))).thenAnswer(invocation -> {
            called.countDown();
            return null;
        });
        KnowledgeTaskDispatcherImpl dispatcher = new KnowledgeTaskDispatcherImpl(
                taskService, eventService, restTemplate, "http://localhost:8000"
        );

        assertEquals(1, dispatcher.dispatchPending(10));

        assertTrue(called.await(2, TimeUnit.SECONDS));
    }

    @Test
    void duplicateDispatchDoesNotCallAiWhenClaimFails() {
        KnowledgeIndexTaskService taskService = mock(KnowledgeIndexTaskService.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(taskService.claimPendingByBusinessTaskId("KT-DUPLICATE")).thenReturn(null);
        KnowledgeTaskDispatcherImpl dispatcher = new KnowledgeTaskDispatcherImpl(
                taskService, mock(KnowledgeTaskEventService.class), restTemplate, "http://localhost:8000"
        );

        dispatcher.dispatch("KT-DUPLICATE");

        verify(restTemplate, never()).postForObject(any(String.class), any(), eq(Map.class));
    }

    @Test
    void responseTimeoutDoesNotMarkAcceptedExecutionFailed() throws Exception {
        KnowledgeIndexTaskService taskService = mock(KnowledgeIndexTaskService.class);
        KnowledgeTaskEventService eventService = mock(KnowledgeTaskEventService.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        KnowledgeIndexTask task = task(3L, "KT-UNCERTAIN");
        CountDownLatch called = new CountDownLatch(1);
        when(taskService.claimPendingByBusinessTaskId(task.getTaskId())).thenReturn(task);
        when(restTemplate.postForObject(any(String.class), any(), eq(Map.class))).thenAnswer(invocation -> {
            called.countDown();
            throw new ResourceAccessException("response timed out");
        });
        KnowledgeTaskDispatcherImpl dispatcher = new KnowledgeTaskDispatcherImpl(
                taskService, eventService, restTemplate, "http://localhost:8000"
        );

        dispatcher.dispatch(task.getTaskId());

        assertTrue(called.await(2, TimeUnit.SECONDS));
        verify(taskService, timeout(1000).times(0)).markFailedExecution(any(), any(), any(), any());
        verify(eventService, timeout(1000).times(1)).record(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any());
    }

    @Test
    void staleFailureDoesNotAppendEventToNewerExecutionTimeline() throws Exception {
        KnowledgeIndexTaskService taskService = mock(KnowledgeIndexTaskService.class);
        KnowledgeTaskEventService eventService = mock(KnowledgeTaskEventService.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        KnowledgeIndexTask task = task(4L, "KT-STALE-FAILURE");
        when(taskService.claimPendingByBusinessTaskId(task.getTaskId())).thenReturn(task);
        when(taskService.markFailedExecution(task.getId(), task.getExecutionToken(), "AI_SERVICE_TRIGGER_FAILED", "server error"))
                .thenReturn(false);
        when(restTemplate.postForObject(any(String.class), any(), eq(Map.class))).thenThrow(new IllegalStateException("server error"));
        KnowledgeTaskDispatcherImpl dispatcher = new KnowledgeTaskDispatcherImpl(
                taskService, eventService, restTemplate, "http://localhost:8000"
        );

        dispatcher.dispatch(task.getTaskId());

        verify(eventService, timeout(1000).times(1)).record(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any());
    }

    private KnowledgeIndexTask task(Long id, String taskId) {
        KnowledgeIndexTask task = new KnowledgeIndexTask();
        task.setId(id);
        task.setTaskId(taskId);
        task.setTaskType("PROCESS_DOC_UPLOAD");
        task.setStatus("DISPATCHING");
        task.setExecutionToken("execution-" + id);
        task.setAttemptNo(1);
        task.setProgressCurrent(1);
        task.setProgressTotal(10);
        return task;
    }
}
