package com.aimall.server.order;

public enum OrderStatus {
    WAIT_PAY(0),
    WAIT_SHIP(1),
    SHIPPED(2),
    COMPLETED(3),
    CLOSED(4),
    INVALID(5);

    private final int code;

    OrderStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
