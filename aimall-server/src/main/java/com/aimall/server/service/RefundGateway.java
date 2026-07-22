package com.aimall.server.service;

import java.math.BigDecimal;

public interface RefundGateway {
    String channel();
    RefundResult refund(String requestId, String orderSn, BigDecimal amount);
    QueryRefundResult queryRefund(String requestId, String orderSn, BigDecimal expectedAmount);

    record RefundResult(String transactionNo) {
    }

    record QueryRefundResult(String status, String transactionNo, BigDecimal refundAmount, String rawResponse) {
    }
}
