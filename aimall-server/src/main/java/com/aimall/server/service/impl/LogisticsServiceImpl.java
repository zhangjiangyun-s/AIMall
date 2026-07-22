package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsLogisticsEvent;
import com.aimall.server.entity.OmsOrder;
import com.aimall.server.entity.OmsOrderItem;
import com.aimall.server.entity.OmsShipment;
import com.aimall.server.entity.OmsShipmentItem;
import com.aimall.server.mapper.OmsLogisticsEventMapper;
import com.aimall.server.mapper.OmsOrderItemMapper;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.mapper.OmsShipmentItemMapper;
import com.aimall.server.mapper.OmsShipmentMapper;
import com.aimall.server.service.LogisticsService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class LogisticsServiceImpl implements LogisticsService {

    private static final List<String> EVENT_CODES = List.of("SHIPPED", "IN_TRANSIT", "DELIVERY", "DELIVERED", "EXCEPTION");
    private final OmsOrderMapper orderMapper;
    private final OmsOrderItemMapper orderItemMapper;
    private final OmsShipmentMapper shipmentMapper;
    private final OmsShipmentItemMapper shipmentItemMapper;
    private final OmsLogisticsEventMapper eventMapper;
    private final ShipmentEligibilityService eligibilityService;

    public LogisticsServiceImpl(
            OmsOrderMapper orderMapper,
            OmsOrderItemMapper orderItemMapper,
            OmsShipmentMapper shipmentMapper,
            OmsShipmentItemMapper shipmentItemMapper,
            OmsLogisticsEventMapper eventMapper,
            ShipmentEligibilityService eligibilityService
    ) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.shipmentMapper = shipmentMapper;
        this.shipmentItemMapper = shipmentItemMapper;
        this.eventMapper = eventMapper;
        this.eligibilityService = eligibilityService;
    }

    @Override
    public Map<String, Object> shipWholeOrder(Long orderId, String carrierCode, String carrierName, String trackingNo) {
        List<ShipmentItemRequest> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OmsOrderItem>().eq(OmsOrderItem::getOrderId, orderId)
        ).stream().map(item -> new ShipmentItemRequest(
                item.getId(),
                item.getProductQuantity()
                        - safeQuantity(item.getShippedQuantity())
                        - safeQuantity(item.getReturnReservedQuantity())
                        - safeQuantity(item.getRefundedQuantity())
        )).filter(item -> item.quantity() > 0).toList();
        return ship(orderId, carrierCode, carrierName, trackingNo, items);
    }

    @Override
    @Transactional
    public Map<String, Object> ship(
            Long orderId,
            String carrierCode,
            String carrierName,
            String trackingNo,
            List<ShipmentItemRequest> requestedItems
    ) {
        String normalizedCarrier = carrierCode == null ? "" : carrierCode.trim().toUpperCase();
        String normalizedTracking = trackingNo == null ? "" : trackingNo.trim();
        OmsShipment replay = shipmentMapper.selectOne(
                new LambdaQueryWrapper<OmsShipment>()
                        .eq(OmsShipment::getCarrierCode, normalizedCarrier)
                        .eq(OmsShipment::getTrackingNo, normalizedTracking)
                        .last("limit 1")
        );
        if (replay != null) {
            if (!orderId.equals(replay.getOrderId())) {
                throw new RuntimeException("物流幂等键已被其他订单使用");
            }
            return toMap(replay);
        }
        OmsOrder order = orderMapper.selectById(orderId);
        if (carrierCode == null || carrierCode.isBlank() || carrierName == null || carrierName.isBlank()
                || trackingNo == null || trackingNo.isBlank()) {
            throw new IllegalArgumentException("承运商编码、名称和物流单号不能为空");
        }
        if (requestedItems == null || requestedItems.isEmpty()) {
            throw new RuntimeException("没有可发货的订单商品");
        }
        Map<Long, Integer> quantities = requestedItems.stream().collect(Collectors.toMap(
                ShipmentItemRequest::orderItemId,
                item -> item.quantity() == null ? 0 : item.quantity(),
                Integer::sum
        ));
        eligibilityService.assertEligible(order, quantities);
        LocalDateTime now = LocalDateTime.now();
        OmsShipment shipment = new OmsShipment();
        shipment.setShipmentSn("SHP" + UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase());
        shipment.setOrderId(orderId);
        shipment.setOrderSn(order.getOrderSn());
        shipment.setCarrierCode(normalizedCarrier);
        shipment.setCarrierName(carrierName.trim());
        shipment.setTrackingNo(normalizedTracking);
        shipment.setStatus("SHIPPED");
        shipment.setShippedAt(now);
        shipment.setCreateTime(now);
        shipment.setUpdateTime(now);
        shipmentMapper.insert(shipment);
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            if (entry.getValue() <= 0 || orderItemMapper.addShippedQuantity(entry.getKey(), orderId, entry.getValue()) != 1) {
                throw new RuntimeException("订单项发货数量超过剩余可发数量");
            }
            OmsShipmentItem shipmentItem = new OmsShipmentItem();
            shipmentItem.setShipmentId(shipment.getId());
            shipmentItem.setOrderItemId(entry.getKey());
            shipmentItem.setQuantity(entry.getValue());
            shipmentItemMapper.insert(shipmentItem);
        }
        if (order.getStatus() == 1 && orderMapper.ship(orderId, carrierName.trim(), trackingNo.trim(), now) != 1) {
            throw new RuntimeException("订单状态已变化，发货失败");
        }
        insertEvent(shipment.getId(), "SHIPPED", now, "", "商家已发货", "ADMIN");
        return toMap(shipment);
    }

    @Override
    @Transactional
    public void appendEvent(Long shipmentId, String eventCode, LocalDateTime eventTime, String location, String description) {
        String normalizedCode = eventCode == null ? "" : eventCode.trim().toUpperCase();
        if (!EVENT_CODES.contains(normalizedCode)) {
            throw new IllegalArgumentException("不支持的物流事件类型");
        }
        OmsShipment shipment = shipmentMapper.selectById(shipmentId);
        if (shipment == null) {
            throw new RuntimeException("包裹不存在");
        }
        LocalDateTime actualTime = eventTime == null ? LocalDateTime.now() : eventTime;
        if (shipmentMapper.updateStatus(shipmentId, normalizedCode, actualTime) != 1) {
            throw new RuntimeException("包裹状态已终结或发生变化");
        }
        insertEvent(shipmentId, normalizedCode, actualTime, location, description, "ADMIN");
    }

    @Override
    public List<Map<String, Object>> listForMember(Long memberId, Long orderId) {
        OmsOrder order = orderMapper.selectById(orderId);
        if (order == null || !memberId.equals(order.getMemberId()) || order.getDeleteStatus() == 1) {
            throw new RuntimeException("订单不存在");
        }
        return shipmentMapper.selectList(
                new LambdaQueryWrapper<OmsShipment>().eq(OmsShipment::getOrderId, orderId).orderByAsc(OmsShipment::getId)
        ).stream().map(shipment -> {
            Map<String, Object> result = toMap(shipment);
            result.put("items", shipmentItemMapper.selectList(
                    new LambdaQueryWrapper<OmsShipmentItem>().eq(OmsShipmentItem::getShipmentId, shipment.getId())
            ));
            result.put("events", eventMapper.selectList(
                    new LambdaQueryWrapper<OmsLogisticsEvent>()
                            .eq(OmsLogisticsEvent::getShipmentId, shipment.getId())
                            .orderByDesc(OmsLogisticsEvent::getEventTime)
            ));
            return result;
        }).toList();
    }

    private void insertEvent(Long shipmentId, String code, LocalDateTime time, String location, String description, String source) {
        OmsLogisticsEvent event = new OmsLogisticsEvent();
        event.setShipmentId(shipmentId);
        event.setEventCode(code);
        event.setEventTime(time);
        event.setLocation(location == null ? "" : location.trim());
        event.setDescription(description == null || description.isBlank() ? code : description.trim());
        event.setSource(source);
        event.setCreateTime(LocalDateTime.now());
        eventMapper.insert(event);
    }

    private int safeQuantity(Integer value) {
        return value == null ? 0 : value;
    }

    private Map<String, Object> toMap(OmsShipment shipment) {
        Map<String, Object> data = new HashMap<>();
        data.put("shipmentId", shipment.getId());
        data.put("shipmentSn", shipment.getShipmentSn());
        data.put("orderId", shipment.getOrderId());
        data.put("carrierCode", shipment.getCarrierCode());
        data.put("carrierName", shipment.getCarrierName());
        data.put("trackingNo", shipment.getTrackingNo());
        data.put("status", shipment.getStatus());
        data.put("shippedAt", shipment.getShippedAt());
        data.put("deliveredAt", shipment.getDeliveredAt());
        return data;
    }
}
