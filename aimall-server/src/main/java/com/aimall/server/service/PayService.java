package com.aimall.server.service;

import java.util.Map;

public interface PayService {
    void simulatePay(Long orderId, Long memberId);
    Map<String, Object> createAlipayPayment(Long orderId, Long memberId);
    Map<String, Object> queryAlipayPayment(Long orderId, Long memberId);
    String handleAlipayNotify(Map<String, String> params);
    String handleAlipayReturn(Map<String, String> params);
    Map<String, Object> getPayStatus(Long orderId, Long memberId);
}
