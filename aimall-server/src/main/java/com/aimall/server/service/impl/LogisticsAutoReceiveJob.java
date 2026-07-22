package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsShipment;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.mapper.OmsShipmentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class LogisticsAutoReceiveJob {

    private final OmsShipmentMapper shipmentMapper;
    private final OmsOrderMapper orderMapper;

    public LogisticsAutoReceiveJob(OmsShipmentMapper shipmentMapper, OmsOrderMapper orderMapper) {
        this.shipmentMapper = shipmentMapper;
        this.orderMapper = orderMapper;
    }

    @Scheduled(fixedDelayString = "${aimall.logistics.auto-receive-scan-ms:60000}")
    public void autoReceiveDeliveredOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        shipmentMapper.selectList(
                new LambdaQueryWrapper<OmsShipment>()
                        .eq(OmsShipment::getStatus, "DELIVERED")
                        .le(OmsShipment::getDeliveredAt, cutoff)
                        .orderByAsc(OmsShipment::getDeliveredAt)
                        .last("LIMIT 100")
        ).stream().map(OmsShipment::getOrderId).distinct()
                .forEach(orderId -> orderMapper.autoConfirmReceived(orderId, cutoff, LocalDateTime.now()));
    }
}
