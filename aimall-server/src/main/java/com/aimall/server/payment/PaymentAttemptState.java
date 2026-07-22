package com.aimall.server.payment;

public enum PaymentAttemptState {
    INIT,
    CREATED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    QUERYING,
    CANCELLED,
    MANUAL_REVIEW_REQUIRED
}
