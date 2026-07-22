package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsOrder;
import com.aimall.server.entity.OmsOrderReturnApply;
import com.aimall.server.entity.OmsPaymentRecord;
import com.aimall.server.entity.OmsRefundRecord;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.mapper.OmsOrderReturnApplyMapper;
import com.aimall.server.mapper.OmsPaymentRecordMapper;
import com.aimall.server.mapper.OmsRefundRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import com.aimall.server.mapper.OmsOrderItemMapper;
import com.aimall.server.mapper.OmsOrderReturnItemMapper;
import com.aimall.server.entity.OmsOrderItem;
import com.aimall.server.entity.OmsOrderReturnItem;
import com.aimall.server.service.ReturnApplyService;
import com.aimall.server.service.RefundGateway;

import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ReturnApplyServiceImplTest {

    private OmsOrderReturnApplyMapper returnApplyMapper;
    private OmsOrderMapper orderMapper;
    private OmsPaymentRecordMapper paymentRecordMapper;
    private OmsRefundRecordMapper refundRecordMapper;
    private ApplicationEventPublisher eventPublisher;
    private OmsOrderItemMapper orderItemMapper;
    private OmsOrderReturnItemMapper returnItemMapper;
    private RefundGateway refundGateway;
    private ReturnApplyServiceImpl returnService;

    @BeforeEach
    void setUp() {
        returnApplyMapper = mock(OmsOrderReturnApplyMapper.class);
        orderMapper = mock(OmsOrderMapper.class);
        paymentRecordMapper = mock(OmsPaymentRecordMapper.class);
        refundRecordMapper = mock(OmsRefundRecordMapper.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        orderItemMapper = mock(OmsOrderItemMapper.class);
        returnItemMapper = mock(OmsOrderReturnItemMapper.class);
        refundGateway = mock(RefundGateway.class);
        when(refundGateway.channel()).thenReturn("ALIPAY_SANDBOX");
        returnService = new ReturnApplyServiceImpl(
                returnApplyMapper,
                orderMapper,
                paymentRecordMapper,
                refundRecordMapper,
                eventPublisher,
                orderItemMapper,
                returnItemMapper,
                refundGateway
        );
    }

    @Test
    void applyReservesSelectedOrderItemQuantityAndCalculatesServerAmount() {
        OmsOrder order = paidOrder();
        order.setStatus(2);
        OmsOrderItem orderItem = new OmsOrderItem();
        orderItem.setId(200L);
        orderItem.setOrderId(100L);
        orderItem.setProductId(300L);
        orderItem.setProductQuantity(2);
        orderItem.setRealAmount(new BigDecimal("80.00"));
        when(orderMapper.selectById(100L)).thenReturn(order);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(orderItem));
        when(returnApplyMapper.insertIgnore(any())).thenAnswer(invocation -> {
            OmsOrderReturnApply value = invocation.getArgument(0);
            value.setId(10L);
            return 1;
        });
        when(orderItemMapper.reserveReturnQuantity(200L, 100L, 1)).thenReturn(1);

        OmsOrderReturnApply result = returnService.apply(
                1L,
                100L,
                "商品破损",
                "只退一件",
                List.of(new ReturnApplyService.ReturnItemRequest(200L, 1))
        );

        assertEquals(new BigDecimal("40.0000"), result.getReturnAmount());
        ArgumentCaptor<OmsOrderReturnItem> itemCaptor = ArgumentCaptor.forClass(OmsOrderReturnItem.class);
        verify(returnItemMapper).insert(itemCaptor.capture());
        assertEquals(1, itemCaptor.getValue().getQuantity());
        assertEquals(new BigDecimal("40.0000"), itemCaptor.getValue().getRefundAmount());
    }

    @Test
    void refundCreatesPersistentTaskAndPublishesAfterCommitEvent() {
        OmsOrderReturnApply apply = approvedApply();
        OmsOrder order = paidOrder();
        OmsPaymentRecord payment = paidRecord();
        when(returnApplyMapper.selectById(10L)).thenReturn(apply);
        when(orderMapper.selectById(100L)).thenReturn(order);
        when(paymentRecordMapper.selectOne(any())).thenReturn(payment);
        when(refundRecordMapper.reserve(any())).thenAnswer(invocation -> {
            OmsRefundRecord record = invocation.getArgument(0);
            record.setId(1000L);
            return 1;
        });
        when(returnApplyMapper.transition(10L, 1, 5, "退款任务已创建")).thenReturn(1);

        OmsOrderReturnApply result = returnService.refund(10L, "refund-request-1", "审核退款");

        assertEquals(5, result.getStatus());
        ArgumentCaptor<OmsRefundRecord> refundCaptor = ArgumentCaptor.forClass(OmsRefundRecord.class);
        verify(refundRecordMapper).reserve(refundCaptor.capture());
        assertEquals("ALIPAY_SANDBOX", refundCaptor.getValue().getRefundChannel());
        ArgumentCaptor<RefundRequestedEvent> eventCaptor = ArgumentCaptor.forClass(RefundRequestedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertEquals(1000L, eventCaptor.getValue().refundRecordId());
        verify(returnApplyMapper).transition(10L, 1, 5, "退款任务已创建");
        verifyNoMoreInteractions(eventPublisher);
    }

    @Test
    void finalPartialRefundReceivesRoundingRemainder() {
        OmsOrder order = paidOrder();
        order.setStatus(2);
        OmsOrderItem orderItem = new OmsOrderItem();
        orderItem.setId(200L);
        orderItem.setOrderId(100L);
        orderItem.setProductId(300L);
        orderItem.setProductQuantity(3);
        orderItem.setRefundedQuantity(2);
        orderItem.setReturnReservedQuantity(0);
        orderItem.setRealAmount(new BigDecimal("10.00"));
        when(orderMapper.selectById(100L)).thenReturn(order);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(orderItem));
        when(returnItemMapper.selectCompletedRefundAmount(200L)).thenReturn(new BigDecimal("6.66"));
        when(returnApplyMapper.insertIgnore(any())).thenAnswer(invocation -> {
            OmsOrderReturnApply value = invocation.getArgument(0);
            value.setId(11L);
            return 1;
        });
        when(orderItemMapper.reserveReturnQuantity(200L, 100L, 1)).thenReturn(1);

        OmsOrderReturnApply result = returnService.apply(
                1L, 100L, "分次退款", "最后一件", List.of(new ReturnApplyService.ReturnItemRequest(200L, 1))
        );

        assertEquals(new BigDecimal("3.3400"), result.getReturnAmount());
        ArgumentCaptor<OmsOrderReturnItem> itemCaptor = ArgumentCaptor.forClass(OmsOrderReturnItem.class);
        verify(returnItemMapper).insert(itemCaptor.capture());
        assertEquals(new BigDecimal("3.3400"), itemCaptor.getValue().getRefundAmount());
    }

    @Test
    void failedRefundCanBeManuallyRetried() {
        OmsOrderReturnApply apply = approvedApply();
        apply.setStatus(5);
        OmsRefundRecord record = new OmsRefundRecord();
        record.setId(1000L);
        record.setReturnApplyId(10L);
        when(returnApplyMapper.selectById(10L)).thenReturn(apply);
        when(refundRecordMapper.retryFailed(10L, "人工重试：渠道恢复")).thenReturn(1);
        when(refundRecordMapper.selectOne(any())).thenReturn(record);

        returnService.retryFailedRefund(10L, "渠道恢复");

        verify(eventPublisher).publishEvent(new RefundRequestedEvent(1000L));
    }

    @Test
    void closingFailedRefundReleasesReservedQuantity() {
        OmsOrderReturnApply apply = approvedApply();
        apply.setStatus(5);
        OmsOrderReturnItem item = new OmsOrderReturnItem();
        item.setOrderItemId(200L);
        item.setQuantity(1);
        when(returnApplyMapper.selectById(10L)).thenReturn(apply);
        when(refundRecordMapper.closeFailed(10L, "人工终止")).thenReturn(1);
        when(returnApplyMapper.transition(10L, 5, 6, "人工终止")).thenReturn(1);
        when(returnItemMapper.selectList(any())).thenReturn(List.of(item));
        when(orderItemMapper.releaseReturnQuantity(200L, 1)).thenReturn(1);

        OmsOrderReturnApply result = returnService.closeFailedRefund(10L, "人工终止");

        assertEquals(6, result.getStatus());
        verify(orderItemMapper).releaseReturnQuantity(200L, 1);
    }

    @Test
    void requestIdOwnedByAnotherReturnIsRejectedInsteadOfFakingReplay() {
        OmsOrderReturnApply apply = approvedApply();
        OmsRefundRecord another = new OmsRefundRecord();
        another.setId(2000L);
        another.setRequestId("shared-request");
        another.setReturnApplyId(999L);
        another.setRefundStatus("SUCCEEDED");
        when(returnApplyMapper.selectById(10L)).thenReturn(apply);
        when(orderMapper.selectById(100L)).thenReturn(paidOrder());
        when(paymentRecordMapper.selectOne(any())).thenReturn(paidRecord());
        when(refundRecordMapper.reserve(any())).thenReturn(0);
        when(refundRecordMapper.selectOne(any())).thenReturn(another);

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> returnService.refund(10L, "shared-request", "退款")
        );

        assertEquals("退款 requestId 已被其他售后申请使用", error.getMessage());
        assertEquals(1, apply.getStatus());
    }

    @Test
    void financialHoldBlocksRefundBeforeTaskCreation() {
        OmsOrderReturnApply apply = approvedApply();
        OmsOrder order = paidOrder();
        order.setFinancialHold(1);
        when(returnApplyMapper.selectById(10L)).thenReturn(apply);
        when(orderMapper.selectById(100L)).thenReturn(order);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> returnService.refund(10L, "held-refund", "退款"));

        assertEquals("订单存在资金对账差异，已冻结退款", error.getMessage());
        verify(refundRecordMapper, org.mockito.Mockito.never()).reserve(any());
    }

    private OmsOrderReturnApply approvedApply() {
        OmsOrderReturnApply apply = new OmsOrderReturnApply();
        apply.setId(10L);
        apply.setOrderId(100L);
        apply.setOrderSn("ORDER-100");
        apply.setMemberId(1L);
        apply.setStatus(1);
        apply.setReturnAmount(new BigDecimal("100.00"));
        return apply;
    }

    private OmsOrder paidOrder() {
        OmsOrder order = new OmsOrder();
        order.setId(100L);
        order.setMemberId(1L);
        order.setOrderSn("ORDER-100");
        order.setDeleteStatus(0);
        order.setPayAmount(new BigDecimal("100.00"));
        return order;
    }

    private OmsPaymentRecord paidRecord() {
        OmsPaymentRecord payment = new OmsPaymentRecord();
        payment.setOrderId(100L);
        payment.setPayStatus("PAID");
        payment.setAmount(new BigDecimal("100.00"));
        return payment;
    }
}
