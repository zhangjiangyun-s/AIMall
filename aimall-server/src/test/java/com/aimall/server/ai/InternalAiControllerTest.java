package com.aimall.server.ai;

import com.aimall.server.entity.KnowledgeDoc;
import com.aimall.server.entity.KnowledgeChunk;
import com.aimall.server.entity.KnowledgeIndexTask;
import com.aimall.server.mapper.OmsOrderItemMapper;
import com.aimall.server.service.AddressService;
import com.aimall.server.service.AiActionExecutionService;
import com.aimall.server.service.CartService;
import com.aimall.server.service.CouponService;
import com.aimall.server.service.KnowledgeChunkService;
import com.aimall.server.service.KnowledgeDocService;
import com.aimall.server.service.KnowledgeIndexTaskService;
import com.aimall.server.service.KnowledgeTaskExecutionGuard;
import com.aimall.server.service.OrderService;
import com.aimall.server.service.ProductService;
import com.aimall.server.service.ReturnApplyService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalAiControllerTest {

    @Test
    void documentRetrievalUsesOnlyCurrentVersionChunks() {
        KnowledgeDocService docs = mock(KnowledgeDocService.class);
        KnowledgeChunkService chunks = mock(KnowledgeChunkService.class);
        KnowledgeDoc legacy = knowledgeDoc(1L, null, "ENABLED", "Legacy return policy");
        legacy.setContent("Legacy unversioned content must not be searchable");
        KnowledgeDoc current = knowledgeDoc(33L, 21L, "ACTIVE", "Return policy");
        KnowledgeChunk currentChunk = new KnowledgeChunk();
        currentChunk.setId(250L);
        currentChunk.setDocId(33L);
        currentChunk.setDocVersionId(21L);
        currentChunk.setDocVersion(1);
        currentChunk.setChunkNo(1);
        currentChunk.setTitle("Return policy");
        currentChunk.setMaskedContent("Current version supports seven-day returns when conditions are met.");
        currentChunk.setStatus("ACTIVE");
        currentChunk.setSourceType("POLICY");
        currentChunk.setVisibilityScope("PUBLIC_USER");
        currentChunk.setTenantId("default");
        when(docs.listAll()).thenReturn(List.of(legacy, current));
        when(chunks.listActive()).thenReturn(List.of(currentChunk));

        InternalAiController controller = controller(docs, chunks, mock(KnowledgeIndexTaskService.class));

        List<Map<String, Object>> result = controller.knowledge(
                null, "seven-day returns", 5, null, null, null
        ).getData();

        assertEquals(1, result.size());
        assertEquals(33L, result.get(0).get("id"));
        assertEquals("Current version supports seven-day returns when conditions are met.", result.get(0).get("content"));
    }

    @Test
    void rebuildReturnsTraceableTaskIdsForEveryCurrentVersion() {
        KnowledgeDocService docs = mock(KnowledgeDocService.class);
        KnowledgeIndexTaskService tasks = mock(KnowledgeIndexTaskService.class);
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setId(7L);
        doc.setCurrentVersionId(9L);
        doc.setStatus("ACTIVE");
        KnowledgeDoc disabled = new KnowledgeDoc();
        disabled.setId(8L);
        disabled.setCurrentVersionId(10L);
        disabled.setStatus("DISABLED");
        KnowledgeIndexTask task = new KnowledgeIndexTask();
        task.setTaskId("TASK-9");
        task.setStatus("PENDING");
        when(docs.listAll()).thenReturn(List.of(doc, disabled));
        when(tasks.createUploadDocTask(7L, 9L, "INTERNAL_REBUILD")).thenReturn(task);

        InternalAiController controller = controller(docs, mock(KnowledgeChunkService.class), tasks);

        Map<String, Object> result = controller.rebuildKnowledgeChunks().getData();
        List<?> returnedTasks = (List<?>) result.get("tasks");
        Map<?, ?> returnedTask = (Map<?, ?>) returnedTasks.get(0);

        assertEquals(1, result.get("docCount"));
        assertEquals(1, result.get("taskCount"));
        assertEquals("TASK-9", returnedTask.get("taskId"));
        assertEquals(9L, returnedTask.get("docVersionId"));
        verify(tasks, never()).createUploadDocTask(8L, 10L, "INTERNAL_REBUILD");
    }

    private KnowledgeDoc knowledgeDoc(Long id, Long currentVersionId, String status, String title) {
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setId(id);
        doc.setCurrentVersionId(currentVersionId);
        doc.setStatus(status);
        doc.setTitle(title);
        doc.setSourceType("POLICY");
        doc.setVisibilityScope("PUBLIC_USER");
        doc.setTenantId("default");
        return doc;
    }

    private InternalAiController controller(
            KnowledgeDocService docs,
            KnowledgeChunkService chunks,
            KnowledgeIndexTaskService tasks
    ) {
        return new InternalAiController(
                mock(ProductService.class), chunks, docs, tasks,
                mock(OrderService.class), mock(OmsOrderItemMapper.class), mock(CouponService.class),
                mock(ReturnApplyService.class), mock(AddressService.class), mock(CartService.class),
                mock(AiActionExecutionService.class), mock(KnowledgeTaskExecutionGuard.class)
        );
    }
}
