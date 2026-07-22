package com.aimall.server.service.impl;

import com.aimall.server.mapper.KnowledgeRetrievalEpochMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalEpochServiceTest {

    @Test
    void nextUsesCompareAndSetAndReturnsAdvancedEpoch() {
        KnowledgeRetrievalEpochMapper mapper = mock(KnowledgeRetrievalEpochMapper.class);
        when(mapper.lockCurrent()).thenReturn(41L);
        when(mapper.advance(41L)).thenReturn(1);
        KnowledgeRetrievalEpochService service = new KnowledgeRetrievalEpochService(mapper);

        assertEquals(42L, service.next());
        verify(mapper).advance(41L);
    }

    @Test
    void nextFailsClosedWhenSingletonIsMissingOrCasLoses() {
        KnowledgeRetrievalEpochMapper missing = mock(KnowledgeRetrievalEpochMapper.class);
        KnowledgeRetrievalEpochService missingService = new KnowledgeRetrievalEpochService(missing);
        assertThrows(IllegalStateException.class, missingService::next);

        KnowledgeRetrievalEpochMapper lost = mock(KnowledgeRetrievalEpochMapper.class);
        when(lost.lockCurrent()).thenReturn(8L);
        when(lost.advance(8L)).thenReturn(0);
        KnowledgeRetrievalEpochService lostService = new KnowledgeRetrievalEpochService(lost);
        assertThrows(IllegalStateException.class, lostService::next);
    }
}
