package com.aimall.server.service.impl;

import com.aimall.server.entity.KnowledgeChunk;
import com.aimall.server.mapper.KnowledgeChunkMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.List;
import java.util.stream.IntStream;

class KnowledgeChunkServiceImplTest {

    @BeforeEach
    void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "KnowledgeChunkServiceImplTest"),
                KnowledgeChunk.class
        );
    }

    @Test
    void vectorDeletionCompletesOnlyAfterPendingRowIsAtomicallyUpdated() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        KnowledgeChunk deleted = new KnowledgeChunk();
        deleted.setId(8L);
        deleted.setStatus("DISABLED");
        deleted.setEmbeddingSyncStatus("DELETED");
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(mapper.selectById(8L)).thenReturn(deleted);
        KnowledgeChunkServiceImpl service = new KnowledgeChunkServiceImpl(mapper);

        KnowledgeChunk result = service.completeVectorDeletion(8L, "claim-8");

        assertEquals("DELETED", result.getEmbeddingSyncStatus());
        verify(mapper).update(isNull(), any());
    }

    @Test
    void vectorDeletionDoesNotReportSuccessWhenChunkWasNotPending() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(9L);
        chunk.setEmbeddingSyncStatus("FAILED");
        when(mapper.update(isNull(), any())).thenReturn(0);
        when(mapper.selectById(9L)).thenReturn(chunk);
        KnowledgeChunkServiceImpl service = new KnowledgeChunkServiceImpl(mapper);

        assertThrows(RuntimeException.class, () -> service.completeVectorDeletion(9L, "claim-9"));
    }

    @Test
    void failedHeadCandidatesDoNotBlockLaterDeletionClaims() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        List<KnowledgeChunk> candidates = IntStream.rangeClosed(1, 101)
                .mapToObj(id -> {
                    KnowledgeChunk chunk = new KnowledgeChunk();
                    chunk.setId((long) id);
                    chunk.setEmbeddingSyncStatus("DELETE_PENDING");
                    return chunk;
                })
                .toList();
        when(mapper.selectList(any())).thenReturn(candidates);
        Integer[] updates = IntStream.rangeClosed(1, 101).map(id -> id == 101 ? 1 : 0).boxed().toArray(Integer[]::new);
        when(mapper.update(isNull(), any())).thenReturn(updates[0], java.util.Arrays.copyOfRange(updates, 1, updates.length));
        KnowledgeChunkServiceImpl service = new KnowledgeChunkServiceImpl(mapper);

        List<KnowledgeChunk> claimed = service.claimVectorDeletions(100);

        assertEquals(1, claimed.size());
        assertEquals(101L, claimed.get(0).getId());
    }

    @Test
    void deadVectorDeletionCanBeManuallyReturnedToPending() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        KnowledgeChunk pending = new KnowledgeChunk();
        pending.setId(12L);
        pending.setEmbeddingSyncStatus("DELETE_PENDING");
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(mapper.selectById(12L)).thenReturn(pending);
        KnowledgeChunkServiceImpl service = new KnowledgeChunkServiceImpl(mapper);

        KnowledgeChunk result = service.retryDeadVectorDeletion(12L);

        assertEquals("DELETE_PENDING", result.getEmbeddingSyncStatus());
        verify(mapper).update(isNull(), any());
    }

    @Test
    void manualVectorDeletionRetryRejectsRowsThatAreNoLongerDead() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        when(mapper.update(isNull(), any())).thenReturn(0);
        KnowledgeChunkServiceImpl service = new KnowledgeChunkServiceImpl(mapper);

        assertThrows(IllegalStateException.class, () -> service.retryDeadVectorDeletion(13L));
    }
}
