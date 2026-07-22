package com.aimall.server.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface LogisticsService {
    Map<String, Object> shipWholeOrder(Long orderId, String carrierCode, String carrierName, String trackingNo);

    Map<String, Object> ship(
            Long orderId,
            String carrierCode,
            String carrierName,
            String trackingNo,
            List<ShipmentItemRequest> items
    );

    void appendEvent(Long shipmentId, String eventCode, LocalDateTime eventTime, String location, String description);

    List<Map<String, Object>> listForMember(Long memberId, Long orderId);

    record ShipmentItemRequest(Long orderItemId, Integer quantity) {
    }
}
