package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsRefundRecord;
import com.aimall.server.service.RefundGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class RefundTaskProcessor {

    private static final Logger log = LoggerFactory.getLogger(RefundTaskProcessor.class);

    private final RefundTaskStateService stateService;
    private final RefundGateway refundGateway;

    public RefundTaskProcessor(RefundTaskStateService stateService, RefundGateway refundGateway) {
        this.stateService = stateService;
        this.refundGateway = refundGateway;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRefundRequested(RefundRequestedEvent event) {
        processSafely(event.refundRecordId());
    }

    @Scheduled(fixedDelayString = "${aimall.refund.recovery-scan-ms:10000}")
    public void recoverRefundTasks() {
        for (OmsRefundRecord record : stateService.listRecoverable(50)) {
            processSafely(record.getId());
        }
    }

    public void process(Long refundRecordId) {
        OmsRefundRecord record = stateService.get(refundRecordId);
        if (record == null || "SUCCEEDED".equals(record.getRefundStatus())
                || "FAILED".equals(record.getRefundStatus()) || "CLOSED".equals(record.getRefundStatus())) {
            return;
        }
        if ("REFUND_UNKNOWN".equals(record.getRefundStatus()) || "REFUND_QUERYING".equals(record.getRefundStatus())) {
            queryUnknownRefund(record);
            record = stateService.get(refundRecordId);
            if (record == null || !"CHANNEL_SUCCEEDED".equals(record.getRefundStatus())) return;
        }
        if (!"CHANNEL_SUCCEEDED".equals(record.getRefundStatus())) {
            if (!stateService.claimChannelCall(refundRecordId)) {
                return;
            }
            try {
                RefundGateway.RefundResult result = refundGateway.refund(
                        record.getRequestId(),
                        record.getOrderSn(),
                        record.getAmount()
                );
                stateService.recordChannelSuccess(refundRecordId, result.transactionNo());
            } catch (Exception exception) {
                stateService.recordChannelUnknown(refundRecordId, exception.getMessage());
                return;
            }
        }
        stateService.finalizeBusiness(refundRecordId);
    }

    private void queryUnknownRefund(OmsRefundRecord record) {
        if (!stateService.claimRefundQuery(record.getId())) return;
        try {
            RefundGateway.QueryRefundResult result = refundGateway.queryRefund(
                    record.getRequestId(), record.getOrderSn(), record.getAmount());
            switch (result.status()) {
                case "SUCCEEDED" -> stateService.recordRefundQuerySuccess(record.getId(), result.transactionNo());
                case "NOT_FOUND" -> stateService.recordRefundQueryNotFound(record.getId());
                default -> stateService.recordRefundQueryUnknown(record.getId(), result.status(), result.rawResponse());
            }
        } catch (Exception exception) {
            stateService.recordRefundQueryUnknown(record.getId(), "QUERY_ERROR", exception.getMessage());
        }
    }

    private void processSafely(Long refundRecordId) {
        try {
            process(refundRecordId);
        } catch (Exception exception) {
            log.error("Refund task processing failed, refundRecordId={}", refundRecordId, exception);
        }
    }
}
