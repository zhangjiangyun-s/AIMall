package com.aimall.server.service.impl;

import com.aimall.server.admin.dto.ReconciliationCorrectionRequest;
import com.aimall.server.entity.PaymentCorrectionEvent;
import com.aimall.server.entity.PaymentReconciliationItem;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.mapper.OmsRefundRecordMapper;
import com.aimall.server.mapper.PaymentCorrectionEventMapper;
import com.aimall.server.mapper.PaymentReconciliationItemMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentReconciliationWorkflowServiceTest {
    @Test
    void correctionRejectsInvalidJsonBeforePersistence() {
        PaymentReconciliationItemMapper itemMapper = mock(PaymentReconciliationItemMapper.class);
        PaymentCorrectionEventMapper correctionMapper = mock(PaymentCorrectionEventMapper.class);
        PaymentReconciliationWorkflowService service = new PaymentReconciliationWorkflowService(
                itemMapper, correctionMapper, mock(OmsOrderMapper.class), mock(OmsRefundRecordMapper.class));
        PaymentReconciliationItem item = new PaymentReconciliationItem();
        item.setId(10L);
        item.setResolutionStatus("CLAIMED");
        item.setClaimedBy(7L);
        when(itemMapper.selectById(10L)).thenReturn(item);

        assertThrows(IllegalArgumentException.class, () -> service.submitCorrection(10L, 7L,
                new ReconciliationCorrectionRequest("PAYMENT_STATE", "reason", "evidence", "not-json", "{}")));

        verify(correctionMapper, never()).insert(any(PaymentCorrectionEvent.class));
    }

    @Test
    void correctionRequiresDifferentReviewerAndReleasesHoldOnlyAfterApproval() {
        PaymentReconciliationItemMapper itemMapper = mock(PaymentReconciliationItemMapper.class);
        PaymentCorrectionEventMapper correctionMapper = mock(PaymentCorrectionEventMapper.class);
        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        OmsRefundRecordMapper refundMapper = mock(OmsRefundRecordMapper.class);
        PaymentReconciliationWorkflowService service = new PaymentReconciliationWorkflowService(
                itemMapper, correctionMapper, orderMapper, refundMapper);
        PaymentReconciliationItem item = new PaymentReconciliationItem();
        item.setId(11L);
        item.setOrderId(22L);
        item.setResolutionStatus("CLAIMED");
        item.setClaimedBy(7L);
        when(itemMapper.selectById(11L)).thenReturn(item);
        when(itemMapper.submitForReview(11L, 7L)).thenReturn(1);
        when(correctionMapper.insert(any(PaymentCorrectionEvent.class))).thenAnswer(invocation -> {
            PaymentCorrectionEvent event = invocation.getArgument(0);
            event.setId(33L);
            return 1;
        });
        ReconciliationCorrectionRequest request = new ReconciliationCorrectionRequest(
                "PAYMENT_STATE", "渠道查单证明本地状态滞后", "evidence://query/1", "{\"state\":\"UNKNOWN\"}",
                "{\"state\":\"PAID\"}");
        PaymentCorrectionEvent event = service.submitCorrection(11L, 7L, request);
        event.setOperatorId(7L);
        event.setStatus("PENDING_REVIEW");
        when(correctionMapper.selectById(33L)).thenReturn(event);

        assertThrows(RuntimeException.class,
                () -> service.review(11L, 33L, 7L, true, "APPROVAL-1", "self review"));
        verify(correctionMapper, never()).review(33L, 11L, "APPROVED", 7L, "APPROVAL-1", "self review");

        when(correctionMapper.review(33L, 11L, "APPROVED", 8L, "APPROVAL-2", "evidence verified"))
                .thenReturn(1);
        when(itemMapper.closeAfterReview(11L, 8L, "APPROVAL-2", "evidence verified")).thenReturn(1);
        when(itemMapper.selectCount(any())).thenReturn(0L);
        service.review(11L, 33L, 8L, true, "APPROVAL-2", "evidence verified");

        verify(orderMapper).releaseFinancialHold(22L);
    }
}
