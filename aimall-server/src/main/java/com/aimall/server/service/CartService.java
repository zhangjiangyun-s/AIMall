package com.aimall.server.service;

import com.aimall.server.entity.OmsCartItem;

import java.util.List;

public interface CartService {
    OmsCartItem add(Long memberId, Long productId, Long productSkuId, Integer quantity);
    List<OmsCartItem> list(Long memberId);
    void updateQuantity(Long memberId, Long id, Integer quantity);
    void delete(Long memberId, Long id);
}
