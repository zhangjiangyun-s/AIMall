package com.aimall.server.service;

import com.aimall.server.entity.OmsOrder;

import java.util.List;

public interface OrderService {
    OmsOrder create(Long memberId, String requestId, Long addressId, Long memberCouponId, List<Long> cartItemIds);

    List<OmsOrder> listByMember(Long memberId);

    OmsOrder getById(Long id);

    void cancel(Long memberId, Long orderId);

    void confirmReceive(Long memberId, Long orderId);
}
