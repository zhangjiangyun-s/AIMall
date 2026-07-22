package com.aimall.server.service.impl;

import com.aimall.server.entity.KnowledgeIndexTask;
import com.aimall.server.mapper.KnowledgeIndexTaskMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import org.springframework.dao.DuplicateKeyException;

class KnowledgeIndexTaskServiceImplTest {

    @BeforeEach
    void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "KnowledgeIndexTaskServiceImplTest"),
                KnowledgeIndexTask.class
        );
    }

    @Test
    void claimReturnsNullWhenAnotherDispatcherWonTheCas() {
        KnowledgeIndexTaskMapper mapper = mock(KnowledgeIndexTaskMapper.class);
        KnowledgeIndexTask task = pendingTask(1L, "KT-CAS-1");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);
        when(mapper.update(any(KnowledgeIndexTask.class), any(LambdaQueryWrapper.class))).thenReturn(0);
        KnowledgeIndexTaskServiceImpl service = new KnowledgeIndexTaskServiceImpl(mapper);

        assertNull(service.claimPendingByBusinessTaskId(task.getTaskId()));
    }

    @Test
    void batchClaimReturnsOnlyTasksWhoseCasSucceeded() {
        KnowledgeIndexTaskMapper mapper = mock(KnowledgeIndexTaskMapper.class);
        KnowledgeIndexTask first = pendingTask(1L, "KT-CAS-1");
        KnowledgeIndexTask second = pendingTask(2L, "KT-CAS-2");
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(first, second));
        when(mapper.update(any(KnowledgeIndexTask.class), any(LambdaQueryWrapper.class))).thenReturn(1, 0);
        KnowledgeIndexTaskServiceImpl service = new KnowledgeIndexTaskServiceImpl(mapper);

        List<KnowledgeIndexTask> claimed = service.claimPendingTasks(10);

        assertEquals(List.of(first), claimed);
        assertEquals("DISPATCHING", first.getStatus());
        assertNotNull(first.getExecutionToken());
        assertEquals(1, first.getAttemptNo());
    }

    @Test
    void concurrentCreateReturnsTaskProtectedByDatabaseUniqueKey() {
        KnowledgeIndexTaskMapper mapper = mock(KnowledgeIndexTaskMapper.class);
        KnowledgeIndexTask concurrent = pendingTask(9L, "KT-CONCURRENT");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null, concurrent);
        doThrow(new DuplicateKeyException("active version duplicate"))
                .when(mapper).insert(any(KnowledgeIndexTask.class));
        KnowledgeIndexTaskServiceImpl service = new KnowledgeIndexTaskServiceImpl(mapper);

        KnowledgeIndexTask result = service.createUploadDocTask(2L, 3L, "MANUAL_REBUILD");

        assertEquals(concurrent, result);
    }

    private KnowledgeIndexTask pendingTask(Long id, String taskId) {
        KnowledgeIndexTask task = new KnowledgeIndexTask();
        task.setId(id);
        task.setTaskId(taskId);
        task.setTaskType("PROCESS_DOC_UPLOAD");
        task.setStatus("PENDING");
        return task;
    }
}
