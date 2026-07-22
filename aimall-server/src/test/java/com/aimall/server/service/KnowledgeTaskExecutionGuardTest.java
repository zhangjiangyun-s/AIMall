package com.aimall.server.service;

import com.aimall.server.entity.KnowledgeIndexTask;
import com.aimall.server.mapper.KnowledgeIndexTaskMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeTaskExecutionGuardTest {

    @Test
    void lateExecutionCannotLockTaskAfterNewAttemptClaimedIt() {
        KnowledgeIndexTaskMapper mapper = mock(KnowledgeIndexTaskMapper.class);
        KnowledgeIndexTask current = new KnowledgeIndexTask();
        current.setTaskId("KT-FENCE-1");
        current.setStatus("RUNNING");
        current.setExecutionToken("new-token");
        current.setAttemptNo(2);
        when(mapper.selectByTaskIdForUpdate("KT-FENCE-1")).thenReturn(current);
        KnowledgeTaskExecutionGuard guard = new KnowledgeTaskExecutionGuard(mapper);

        assertThrows(
                IllegalStateException.class,
                () -> guard.lockActive("KT-FENCE-1", "old-token")
        );
        assertEquals(current, guard.lockActive("KT-FENCE-1", "new-token"));
    }
}
