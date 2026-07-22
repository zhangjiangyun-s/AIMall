package com.aimall.server.payment;

public enum PaymentOrderState {
    INIT,
    CREATED,
    WAITING_PAYMENT,
    PROCESSING,
    PAID,
    FAILED,
    UNKNOWN,
    QUERYING,
    CLOSING,
    CLOSED,
    CLOSE_UNKNOWN,
    PARTIALLY_REFUNDED,
    REFUNDED,
    RISK_HOLD,
    CHARGEBACK,
    CANCELLED_BY_CHANNEL,
    MANUAL_REVIEW_REQUIRED
}
