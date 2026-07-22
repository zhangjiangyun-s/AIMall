package com.aimall.server.service.impl;

import com.aimall.server.entity.AiActionExecution;
import com.aimall.server.mapper.AiActionExecutionMapper;
import com.aimall.server.service.AiActionExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiActionExecutionServiceImplTest {

    private AiActionExecutionMapper executionMapper;
    private AiActionExecutionServiceImpl service;

    @BeforeEach
    void setUp() {
        executionMapper = mock(AiActionExecutionMapper.class);
        service = new AiActionExecutionServiceImpl(executionMapper, new ObjectMapper());
    }

    @Test
    void firstRequestReservesExecutionAndSuccessCanBeReplayed() {
        when(executionMapper.selectOne(any())).thenReturn(null);
        when(executionMapper.insert(any(AiActionExecution.class))).thenReturn(1);
        Map<String, Object> request = Map.of("actionId", "action-1", "productId", 1103, "quantity", 1);

        AiActionExecutionService.Reservation first = service.reserve("action-1", "ADD_TO_CART", 7L, request);

        assertTrue(first.shouldExecute());
        ArgumentCaptor<AiActionExecution> captor = ArgumentCaptor.forClass(AiActionExecution.class);
        verify(executionMapper).insert(captor.capture());
        AiActionExecution stored = captor.getValue();
        stored.setStatus("SUCCEEDED");
        stored.setResultJson("{\"cartItemId\":99,\"quantity\":1}");
        when(executionMapper.selectOne(any())).thenReturn(stored);

        AiActionExecutionService.Reservation replay = service.reserve("action-1", "ADD_TO_CART", 7L, request);

        assertFalse(replay.shouldExecute());
        assertEquals(99, replay.replayResult().get("cartItemId"));
        assertEquals(true, replay.replayResult().get("replayed"));
    }

    @Test
    void sameActionIdCannotBeReusedByAnotherMemberOrArguments() {
        AiActionExecution stored = new AiActionExecution();
        stored.setActionId("action-2");
        stored.setActionType("CLAIM_COUPON");
        stored.setMemberId(7L);
        stored.setRequestHash("different-hash");
        stored.setStatus("SUCCEEDED");
        when(executionMapper.selectOne(any())).thenReturn(stored);

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> service.reserve(
                        "action-2",
                        "CLAIM_COUPON",
                        8L,
                        Map.of("actionId", "action-2", "couponId", 1)
                )
        );

        assertTrue(error.getMessage().contains("其他用户、参数或操作类型"));
    }

    @Test
    void processingActionRejectsConcurrentDuplicate() {
        when(executionMapper.selectOne(any())).thenReturn(null);
        when(executionMapper.insert(any(AiActionExecution.class))).thenReturn(1);
        Map<String, Object> request = Map.of("actionId", "action-3", "orderId", 88);
        service.reserve("action-3", "CANCEL_ORDER", 7L, request);

        ArgumentCaptor<AiActionExecution> captor = ArgumentCaptor.forClass(AiActionExecution.class);
        verify(executionMapper).insert(captor.capture());
        AiActionExecution stored = captor.getValue();
        when(executionMapper.selectOne(any())).thenReturn(stored);

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> service.reserve("action-3", "CANCEL_ORDER", 7L, request)
        );

        assertTrue(error.getMessage().contains("正在执行"));
    }

    @Test
    void failedActionCannotBeRetriedWithSameActionId() {
        AiActionExecution stored = new AiActionExecution();
        stored.setActionId("action-4");
        stored.setActionType("APPLY_RETURN");
        stored.setMemberId(7L);
        stored.setStatus("FAILED");
        Map<String, Object> request = Map.of("actionId", "action-4", "orderId", 88, "reason", "破损");

        when(executionMapper.selectOne(any())).thenReturn(null);
        when(executionMapper.insert(any(AiActionExecution.class))).thenAnswer(invocation -> {
            AiActionExecution inserted = invocation.getArgument(0);
            stored.setRequestHash(inserted.getRequestHash());
            return 1;
        });
        service.reserve("action-4", "APPLY_RETURN", 7L, request);
        when(executionMapper.selectOne(any())).thenReturn(stored);

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> service.reserve("action-4", "APPLY_RETURN", 7L, request)
        );

        assertTrue(error.getMessage().contains("此前执行失败"));
    }

    @Test
    void staleProcessingExecutionsAreMovedToManualRecoveryQueue() {
        when(executionMapper.markStaleForRecovery()).thenReturn(2);

        assertEquals(2, service.markStaleExecutionsForRecovery());

        verify(executionMapper).markStaleForRecovery();
    }
}
