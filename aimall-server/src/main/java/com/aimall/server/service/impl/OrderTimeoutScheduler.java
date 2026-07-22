package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsOrder;
import com.aimall.server.mapper.OmsOrderMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OrderTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutScheduler.class);

    private final OmsOrderMapper orderMapper;
    private final OutboxEventService outboxEventService;

    public OrderTimeoutScheduler(OmsOrderMapper orderMapper, OutboxEventService outboxEventService) {
        this.orderMapper = orderMapper;
        this.outboxEventService = outboxEventService;
    }

    @Scheduled(fixedDelayString = "${aimall.order.timeout-scan-ms:30000}")
    public void closeExpiredOrders() {
        LocalDateTime now = LocalDateTime.now();
        List<OmsOrder> expiredOrders = orderMapper.selectList(
                new LambdaQueryWrapper<OmsOrder>()
                        .eq(OmsOrder::getStatus, 0)
                        .eq(OmsOrder::getDeleteStatus, 0)
                        .isNotNull(OmsOrder::getExpireTime)
                        .le(OmsOrder::getExpireTime, now)
                        .orderByAsc(OmsOrder::getExpireTime)
                        .last("limit 50")
        );
        for (OmsOrder order : expiredOrders) {
            try {
                outboxEventService.enqueue("ORDER", String.valueOf(order.getId()), "PAYMENT_CLOSE_REQUESTED",
                        "PAYMENT_CLOSE:" + order.getId(), java.util.Map.of("orderId", order.getId()));
            } catch (Exception exception) {
                log.error("Close expired order failed, orderId={}", order.getId(), exception);
            }
        }
    }
}
