package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsOrder;
import com.aimall.server.entity.OmsOrderReturnApply;
import com.aimall.server.entity.OmsPaymentRecord;
import com.aimall.server.entity.OmsRefundRecord;
import com.aimall.server.entity.OmsOrderItem;
import com.aimall.server.entity.OmsOrderReturnItem;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.mapper.OmsOrderReturnApplyMapper;
import com.aimall.server.mapper.OmsPaymentRecordMapper;
import com.aimall.server.mapper.OmsRefundRecordMapper;
import com.aimall.server.mapper.OmsOrderItemMapper;
import com.aimall.server.mapper.OmsOrderReturnItemMapper;
import com.aimall.server.mapper.OmsReturnEvidenceMapper;
import com.aimall.server.service.ReturnApplyService;
import com.aimall.server.service.RefundGateway;
import com.aimall.server.outbox.OutboxEventType;
import com.aimall.server.money.MoneyPolicy;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ReturnApplyServiceImpl implements ReturnApplyService {

    private final OmsOrderReturnApplyMapper returnApplyMapper;
    private final OmsOrderMapper orderMapper;
    private final OmsPaymentRecordMapper paymentRecordMapper;
    private final OmsRefundRecordMapper refundRecordMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final OmsOrderItemMapper orderItemMapper;
    private final OmsOrderReturnItemMapper returnItemMapper;
    private final RefundGateway refundGateway;
    private final ReturnWorkflowService workflowService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OmsReturnEvidenceMapper evidenceMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OutboxEventService outboxEventService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MoneySnapshotService moneySnapshotService;

    public ReturnApplyServiceImpl(
            OmsOrderReturnApplyMapper returnApplyMapper,
            OmsOrderMapper orderMapper,
            OmsPaymentRecordMapper paymentRecordMapper,
            OmsRefundRecordMapper refundRecordMapper,
            ApplicationEventPublisher eventPublisher,
            OmsOrderItemMapper orderItemMapper,
            OmsOrderReturnItemMapper returnItemMapper,
            RefundGateway refundGateway
    ) {
        this(returnApplyMapper,orderMapper,paymentRecordMapper,refundRecordMapper,eventPublisher,orderItemMapper,returnItemMapper,refundGateway,null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ReturnApplyServiceImpl(OmsOrderReturnApplyMapper returnApplyMapper,OmsOrderMapper orderMapper,
            OmsPaymentRecordMapper paymentRecordMapper,OmsRefundRecordMapper refundRecordMapper,
            ApplicationEventPublisher eventPublisher,OmsOrderItemMapper orderItemMapper,
            OmsOrderReturnItemMapper returnItemMapper,RefundGateway refundGateway,ReturnWorkflowService workflowService) {
        this.returnApplyMapper = returnApplyMapper;
        this.orderMapper = orderMapper;
        this.paymentRecordMapper = paymentRecordMapper;
        this.refundRecordMapper = refundRecordMapper;
        this.eventPublisher = eventPublisher;
        this.orderItemMapper = orderItemMapper;
        this.returnItemMapper = returnItemMapper;
        this.refundGateway = refundGateway;
        this.workflowService=workflowService;
    }

    @Override
    public List<OmsOrderReturnApply> listByMember(Long memberId) {
        return returnApplyMapper.selectList(
                new LambdaQueryWrapper<OmsOrderReturnApply>()
                        .eq(OmsOrderReturnApply::getMemberId, memberId)
                        .orderByDesc(OmsOrderReturnApply::getId)
        );
    }

    @Override
    public OmsOrderReturnApply getByIdForMember(Long memberId, Long id) {
        OmsOrderReturnApply apply = requireApply(id);
        if (!apply.getMemberId().equals(memberId)) {
            throw new RuntimeException("售后申请不存在");
        }
        return apply;
    }

    @Override
    public OmsOrderReturnApply getLatestByOrderForMember(Long memberId, Long orderId) {
        return returnApplyMapper.selectOne(
                new LambdaQueryWrapper<OmsOrderReturnApply>()
                        .eq(OmsOrderReturnApply::getMemberId, memberId)
                        .eq(OmsOrderReturnApply::getOrderId, orderId)
                        .orderByDesc(OmsOrderReturnApply::getId)
                        .last("limit 1")
        );
    }

    @Override
    public OmsOrderReturnApply apply(Long memberId, Long orderId, String reason, String description) {
        return apply(memberId, orderId, reason, description, List.of());
    }

    @Override
    @Transactional
    public OmsOrderReturnApply apply(
            Long memberId,
            Long orderId,
            String reason,
            String description,
            List<ReturnApplyService.ReturnItemRequest> requestedItems
    ) {
        return apply(memberId, orderId, reason, description, requestedItems, "REFUND");
    }

    @Override
    @Transactional
    public OmsOrderReturnApply apply(Long memberId, Long orderId, String reason, String description,
                                     List<ReturnApplyService.ReturnItemRequest> requestedItems, String type) {
        OmsOrder order = requireMemberOrder(memberId, orderId);
        if (order.getStatus() == null || order.getStatus() == 0 || order.getStatus() == 4 || order.getStatus() == 5) {
            throw new RuntimeException("当前订单状态不支持申请售后");
        }

        List<OmsOrderItem> orderItems = orderItemMapper.selectList(
                new LambdaQueryWrapper<OmsOrderItem>().eq(OmsOrderItem::getOrderId, orderId)
        );
        Map<Long, OmsOrderItem> itemById = orderItems.stream()
                .collect(Collectors.toMap(OmsOrderItem::getId, Function.identity()));
        List<ReturnApplyService.ReturnItemRequest> normalizedItems = normalizeReturnItems(requestedItems, orderItems);
        Map<Long, BigDecimal> refundAmounts = normalizedItems.stream()
                .collect(Collectors.toMap(
                        ReturnApplyService.ReturnItemRequest::orderItemId,
                        item -> refundAmount(itemById.get(item.orderItemId()), item.quantity())
                ));
        BigDecimal returnAmount = refundAmounts.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (returnAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("售后申请没有可退款商品");
        }

        OmsOrderReturnApply apply = new OmsOrderReturnApply();
        apply.setMemberId(memberId);
        apply.setOrderId(orderId);
        apply.setOrderSn(order.getOrderSn());
        String normalizedType = type == null ? "REFUND" : type.trim().toUpperCase();
        if (!"REFUND".equals(normalizedType) && !"RETURN_REFUND".equals(normalizedType)) {
            throw new IllegalArgumentException("售后类型只支持 REFUND 或 RETURN_REFUND");
        }
        apply.setType(normalizedType);
        apply.setStatus(0);
        apply.setReason(reason);
        apply.setDescription(description);
        apply.setReturnAmount(returnAmount);
        apply.setCreateTime(LocalDateTime.now());
        apply.setUpdateTime(LocalDateTime.now());
        apply.setSlaDeadline(LocalDateTime.now().plusHours(48));
        apply.setSlaOverdue(0);
        if (returnApplyMapper.insertIgnore(apply) != 1) {
            throw new RuntimeException("该订单已有进行中的售后申请");
        }
        audit(apply.getId(),null,0,"APPLIED",memberId,"MEMBER",reason);
        for (ReturnApplyService.ReturnItemRequest requested : normalizedItems) {
            OmsOrderItem orderItem = itemById.get(requested.orderItemId());
            if (orderItem == null || orderItemMapper.reserveReturnQuantity(orderItem.getId(), orderId, requested.quantity()) != 1) {
                throw new RuntimeException("订单商品可退数量不足或已被其他售后占用");
            }
            OmsOrderReturnItem returnItem = new OmsOrderReturnItem();
            returnItem.setReturnApplyId(apply.getId());
            returnItem.setOrderId(orderId);
            returnItem.setOrderItemId(orderItem.getId());
            returnItem.setProductId(orderItem.getProductId());
            returnItem.setProductSkuId(orderItem.getProductSkuId());
            returnItem.setQuantity(requested.quantity());
            returnItem.setRefundAmount(refundAmounts.get(orderItem.getId()));
            returnItem.setCreateTime(LocalDateTime.now());
            returnItemMapper.insert(returnItem);
            if (moneySnapshotService != null) moneySnapshotService.record(returnItem);
        }
        return apply;
    }

    @Override
    @Transactional
    public OmsOrderReturnApply retryFailedRefund(Long id, String handleNote) {
        OmsOrderReturnApply apply = requireRefundingApply(id);
        String note = requireHandleNote(handleNote);
        if (refundRecordMapper.retryFailed(id, "人工重试：" + note) != 1) {
            throw new RuntimeException("退款任务不是失败状态或已被其他操作处理");
        }
        OmsRefundRecord retriedRecord = requireRefundRecord(id);
        enqueueRefundRequested(retriedRecord, "RETRY:" + retriedRecord.getRetryCount());
        apply.setHandleNote(note);
        return apply;
    }

    @Override
    @Transactional
    public OmsOrderReturnApply closeFailedRefund(Long id, String handleNote) {
        OmsOrderReturnApply apply = requireRefundingApply(id);
        String note = requireHandleNote(handleNote);
        if (refundRecordMapper.closeFailed(id, note) != 1) {
            throw new RuntimeException("退款任务不是失败状态或已被其他操作处理");
        }
        if (returnApplyMapper.transition(id, 5, 6, note) != 1) {
            throw new RuntimeException("售后状态已变化，关闭失败退款任务失败");
        }
        releaseReservedReturnItems(id);
        apply.setStatus(6);
        apply.setHandleNote(note);
        return apply;
    }

    @Override
    @Transactional
    public OmsOrderReturnApply cancel(Long memberId, Long id) {
        OmsOrderReturnApply apply = getByIdForMember(memberId, id);
        if (apply.getStatus() == null || apply.getStatus() != 0) {
            throw new RuntimeException("当前售后申请不可取消");
        }
        if (returnApplyMapper.transition(id, 0, 4, "用户主动取消") != 1) {
            throw new RuntimeException("售后状态已变化，取消失败");
        }
        audit(id,0,4,"CANCELLED",memberId,"MEMBER","用户主动取消");
        releaseReservedReturnItems(id);
        apply.setStatus(4);
        apply.setHandleNote("用户主动取消");
        return apply;
    }

    @Override
    public List<OmsOrderReturnApply> listAll() {
        return returnApplyMapper.selectList(
                new LambdaQueryWrapper<OmsOrderReturnApply>()
                        .orderByDesc(OmsOrderReturnApply::getId)
        );
    }

    @Override
    public OmsOrderReturnApply getById(Long id) {
        return requireApply(id);
    }

    @Override
    @Transactional
    public OmsOrderReturnApply review(Long id, boolean approved, String handleNote) {
        OmsOrderReturnApply apply = requireApply(id);
        if (apply.getStatus() == null || apply.getStatus() != 0) {
            throw new RuntimeException("当前售后申请不可审核");
        }
        if (approved && evidenceMapper != null
                && evidenceMapper.selectCount(new LambdaQueryWrapper<com.aimall.server.entity.OmsReturnEvidence>()
                .eq(com.aimall.server.entity.OmsReturnEvidence::getReturnApplyId, id)) <= 0) {
            throw new IllegalStateException("售后审核通过前必须至少提交一张图片或一段视频凭证");
        }
        int targetStatus = approved ? 1 : 2;
        if (returnApplyMapper.transition(id, 0, targetStatus, handleNote) != 1) {
            throw new RuntimeException("售后状态已变化，审核失败");
        }
        audit(id,0,targetStatus,approved?"APPROVED":"REJECTED",null,"ADMIN",handleNote);
        if (!approved) {
            releaseReservedReturnItems(id);
        }
        apply.setStatus(targetStatus);
        apply.setHandleNote(handleNote);
        return apply;
    }

    @Override
    @Transactional
    public OmsOrderReturnApply refund(Long id, String requestId, String handleNote) {
        OmsOrderReturnApply apply = requireApply(id);
        boolean refundable = "RETURN_REFUND".equals(apply.getType())
                ? apply.getStatus() != null && apply.getStatus() == 8 && "ACCEPTED".equals(apply.getInspectionResult())
                : apply.getStatus() != null && apply.getStatus() == 1;
        if (!refundable) {
            throw new RuntimeException("当前售后申请不可执行退款");
        }
        String normalizedRequestId = normalizeRequestId(requestId);
        OmsOrder order = orderMapper.selectById(apply.getOrderId());
        if (order == null || order.getDeleteStatus() == 1) {
            throw new RuntimeException("退款订单不存在");
        }
        if (Integer.valueOf(1).equals(order.getFinancialHold())) {
            throw new RuntimeException("订单存在资金对账差异，已冻结退款");
        }
        BigDecimal refundAmount = requireRefundAmount(apply, order);
        OmsPaymentRecord paymentRecord = paymentRecordMapper.selectOne(
                new LambdaQueryWrapper<OmsPaymentRecord>()
                        .eq(OmsPaymentRecord::getOrderId, order.getId())
                        .last("limit 1")
        );
        if (paymentRecord == null || !("PAID".equals(paymentRecord.getPayStatus()) || "PARTIALLY_REFUNDED".equals(paymentRecord.getPayStatus()))) {
            throw new RuntimeException("订单不存在可退款的支付流水");
        }
        BigDecimal alreadyRefunded = paymentRecord.getRefundedAmount() == null ? BigDecimal.ZERO : paymentRecord.getRefundedAmount();
        if (paymentRecord.getAmount() == null || alreadyRefunded.add(refundAmount).compareTo(paymentRecord.getAmount()) > 0) {
            throw new RuntimeException("退款金额超过支付流水剩余可退金额");
        }

        OmsRefundRecord refundRecord = new OmsRefundRecord();
        refundRecord.setRequestId(normalizedRequestId);
        refundRecord.setReturnApplyId(apply.getId());
        refundRecord.setOrderId(order.getId());
        refundRecord.setOrderSn(order.getOrderSn());
        refundRecord.setRefundChannel(refundGateway.channel());
        refundRecord.setAmount(refundAmount);
        refundRecord.setCurrencyCode(MoneyPolicy.DEFAULT_CURRENCY);
        refundRecord.setCurrencyScale(MoneyPolicy.DEFAULT_CURRENCY_SCALE);
        refundRecord.setHandleNote(handleNote);
        if (refundRecordMapper.reserve(refundRecord) != 1) {
            OmsRefundRecord existing = refundRecordMapper.selectOne(
                    new LambdaQueryWrapper<OmsRefundRecord>()
                            .and(wrapper -> wrapper
                                    .eq(OmsRefundRecord::getRequestId, normalizedRequestId)
                                    .or()
                                    .eq(OmsRefundRecord::getReturnApplyId, apply.getId()))
                            .last("limit 1")
            );
            boolean sameReturnApply = existing != null && apply.getId().equals(existing.getReturnApplyId());
            if (sameReturnApply && "SUCCEEDED".equals(existing.getRefundStatus())) {
                apply.setStatus(3);
                return apply;
            }
            if (sameReturnApply && normalizedRequestId.equals(existing.getRequestId())) {
                apply.setStatus(5);
                return apply;
            }
            if (existing != null && normalizedRequestId.equals(existing.getRequestId())) {
                throw new RuntimeException("退款 requestId 已被其他售后申请使用");
            }
            throw new RuntimeException("该售后申请已经存在退款任务");
        }
        if (moneySnapshotService != null) moneySnapshotService.record(refundRecord);
        if (returnApplyMapper.transition(id, apply.getStatus(), 5, "退款任务已创建") != 1) {
            throw new RuntimeException("售后状态已变化，退款任务创建失败");
        }
        audit(id,apply.getStatus(),5,"REFUNDING",null,"ADMIN",handleNote);
        enqueueRefundRequested(refundRecord, "INITIAL");
        apply.setStatus(5);
        apply.setHandleNote(handleNote);
        return apply;
    }

    private void enqueueRefundRequested(OmsRefundRecord record, String generation) {
        if (outboxEventService == null) {
            eventPublisher.publishEvent(new RefundRequestedEvent(record.getId()));
            return;
        }
        outboxEventService.enqueue("REFUND", String.valueOf(record.getId()), 1L,
                OutboxEventType.REFUND_REQUESTED,
                "REFUND_REQUESTED:" + record.getId() + ":" + generation, 1,
                Map.of("refundRecordId", record.getId(), "orderId", record.getOrderId(),
                        "returnApplyId", record.getReturnApplyId(), "amount", record.getAmount()));
    }

    private BigDecimal requireRefundAmount(OmsOrderReturnApply apply, OmsOrder order) {
        BigDecimal amount = apply.getReturnAmount();
        BigDecimal paidAmount = order.getPayAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("退款金额必须大于 0");
        }
        BigDecimal refunded = order.getRefundedAmount() == null ? BigDecimal.ZERO : order.getRefundedAmount();
        if (paidAmount == null || refunded.add(amount).compareTo(paidAmount) > 0) {
            throw new RuntimeException("退款金额超过订单剩余可退金额");
        }
        return amount;
    }

    private List<ReturnApplyService.ReturnItemRequest> normalizeReturnItems(
            List<ReturnApplyService.ReturnItemRequest> requested,
            List<OmsOrderItem> orderItems
    ) {
        if (requested == null || requested.isEmpty()) {
            return orderItems.stream()
                    .map(item -> new ReturnApplyService.ReturnItemRequest(
                            item.getId(),
                            item.getProductQuantity()
                                    - (item.getReturnReservedQuantity() == null ? 0 : item.getReturnReservedQuantity())
                                    - (item.getRefundedQuantity() == null ? 0 : item.getRefundedQuantity())
                    ))
                    .filter(item -> item.quantity() > 0)
                    .toList();
        }
        Map<Long, Integer> merged = requested.stream().collect(Collectors.toMap(
                ReturnApplyService.ReturnItemRequest::orderItemId,
                item -> item.quantity() == null ? 0 : item.quantity(),
                Integer::sum
        ));
        return merged.entrySet().stream().map(entry -> {
            if (entry.getValue() <= 0) {
                throw new RuntimeException("售后商品数量必须大于 0");
            }
            return new ReturnApplyService.ReturnItemRequest(entry.getKey(), entry.getValue());
        }).toList();
    }

    private BigDecimal refundAmount(OmsOrderItem orderItem, int quantity) {
        if (orderItem == null || orderItem.getProductQuantity() == null || orderItem.getProductQuantity() <= 0) {
            throw new RuntimeException("订单商品不存在");
        }
        BigDecimal legacyLinePaid = orderItem.getRealAmount() == null
                ? orderItem.getProductPrice().multiply(BigDecimal.valueOf(orderItem.getProductQuantity()))
                : orderItem.getRealAmount();
        BigDecimal linePaid = moneySnapshotService == null ? MoneyPolicy.storage(legacyLinePaid)
                : moneySnapshotService.orderItemRealAmount(orderItem.getId(), legacyLinePaid);
        int refundedQuantity = orderItem.getRefundedQuantity() == null ? 0 : orderItem.getRefundedQuantity();
        int reservedQuantity = orderItem.getReturnReservedQuantity() == null ? 0 : orderItem.getReturnReservedQuantity();
        int remainingQuantity = orderItem.getProductQuantity() - refundedQuantity - reservedQuantity;
        if (quantity > remainingQuantity) {
            throw new RuntimeException("订单商品可退数量不足");
        }
        if (quantity == remainingQuantity) {
            BigDecimal legacyCompleted = returnItemMapper.selectCompletedRefundAmount(orderItem.getId());
            BigDecimal completedAmount = moneySnapshotService == null ? MoneyPolicy.storage(legacyCompleted)
                    : moneySnapshotService.completedRefundAmount(orderItem.getId(), legacyCompleted);
            BigDecimal remainingAmount = linePaid.subtract(completedAmount == null ? BigDecimal.ZERO : completedAmount);
            if (remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new RuntimeException("订单商品没有剩余可退款金额");
            }
            return MoneyPolicy.storage(remainingAmount);
        }
        return MoneyPolicy.storage(linePaid.multiply(BigDecimal.valueOf(quantity))
                .divide(BigDecimal.valueOf(orderItem.getProductQuantity()), MoneyPolicy.STORAGE_SCALE,
                        MoneyPolicy.SETTLEMENT_ROUNDING));
    }

    private OmsOrderReturnApply requireRefundingApply(Long id) {
        OmsOrderReturnApply apply = requireApply(id);
        if (apply.getStatus() == null || apply.getStatus() != 5) {
            throw new RuntimeException("售后申请不在退款处理中状态");
        }
        return apply;
    }

    private OmsRefundRecord requireRefundRecord(Long returnApplyId) {
        OmsRefundRecord record = refundRecordMapper.selectOne(
                new LambdaQueryWrapper<OmsRefundRecord>()
                        .eq(OmsRefundRecord::getReturnApplyId, returnApplyId)
                        .last("limit 1")
        );
        if (record == null) {
            throw new RuntimeException("退款任务不存在");
        }
        return record;
    }

    private String requireHandleNote(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("处理说明不能为空");
        }
        return value.trim().substring(0, Math.min(500, value.trim().length()));
    }

    private void releaseReservedReturnItems(Long returnApplyId) {
        for (OmsOrderReturnItem item : returnItemMapper.selectList(
                new LambdaQueryWrapper<OmsOrderReturnItem>().eq(OmsOrderReturnItem::getReturnApplyId, returnApplyId)
        )) {
            if (orderItemMapper.releaseReturnQuantity(item.getOrderItemId(), item.getQuantity()) != 1) {
                throw new RuntimeException("释放售后占用数量失败");
            }
        }
    }

    private String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("退款 requestId 不能为空");
        }
        String normalized = requestId.trim();
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("退款 requestId 长度不能超过 64");
        }
        return normalized;
    }

    private OmsOrder requireMemberOrder(Long memberId, Long orderId) {
        OmsOrder order = orderMapper.selectById(orderId);
        if (order == null || !order.getMemberId().equals(memberId) || (order.getDeleteStatus() != null && order.getDeleteStatus() == 1)) {
            throw new RuntimeException("订单不存在");
        }
        return order;
    }

    private void audit(Long id,Integer from,Integer to,String type,Long operator,String operatorType,String note){if(workflowService!=null)workflowService.record(id,from,to,type,operator,operatorType,note);}

    private OmsOrderReturnApply requireApply(Long id) {
        OmsOrderReturnApply apply = returnApplyMapper.selectById(id);
        if (apply == null) {
            throw new RuntimeException("售后申请不存在");
        }
        return apply;
    }
}
