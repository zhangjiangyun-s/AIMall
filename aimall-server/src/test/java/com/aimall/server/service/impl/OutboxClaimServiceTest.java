package com.aimall.server.service.impl;

import com.aimall.server.entity.OutboxEvent;
import com.aimall.server.mapper.OutboxEventMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxClaimServiceTest {
    @Test
    void returnsOnlyRowsClaimedByLeaseCas() {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        OutboxEvent first = event(1L);
        OutboxEvent second = event(2L);
        when(mapper.listClaimCandidatesForUpdate(50)).thenReturn(List.of(first, second));
        when(mapper.claim(eq(1L), eq("worker-1"), any(LocalDateTime.class))).thenReturn(1);
        when(mapper.claim(eq(2L), eq("worker-1"), any(LocalDateTime.class))).thenReturn(0);

        List<OutboxEvent> claimed = new OutboxClaimService(mapper).claim("worker-1", 50, 60);

        assertEquals(List.of(first), claimed);
        verify(mapper).listClaimCandidatesForUpdate(50);
    }

    private OutboxEvent event(Long id) {
        OutboxEvent event = new OutboxEvent();
        event.setId(id);
        return event;
    }
}
