package com.aimall.server.service;

import com.aimall.server.entity.OmsCartItem;
import com.aimall.server.entity.OmsOrderItem;

import java.util.List;

public interface InventoryService {
    void reserveForCartItems(List<OmsCartItem> cartItems);

    void releaseForOrderItems(List<OmsOrderItem> orderItems);

    void deductForOrderItems(List<OmsOrderItem> orderItems);

    void restoreForOrderItems(List<OmsOrderItem> orderItems);

    default void restoreForOrderItems(List<OmsOrderItem> orderItems, String operationId) {
        restoreForOrderItems(orderItems);
    }
}
