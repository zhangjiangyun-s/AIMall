package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsOrder;
import com.aimall.server.entity.OmsPaymentRecord;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.mapper.OmsPaymentRecordMapper;
import com.aimall.server.payment.AlipayPaymentGateway;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class PaymentCloseService {
    private static final Logger log = LoggerFactory.getLogger(PaymentCloseService.class);
    private final OmsOrderMapper orderMapper;
    private final OmsPaymentRecordMapper paymentMapper;
    private final AlipayPaymentGateway gateway;
    private final PaymentQueryStateService queryStateService;
    private final PaymentCloseStateService closeStateService;

    public PaymentCloseService(OmsOrderMapper orderMapper, OmsPaymentRecordMapper paymentMapper,
                               AlipayPaymentGateway gateway, PaymentQueryStateService queryStateService,
                               PaymentCloseStateService closeStateService) {
        this.orderMapper = orderMapper;
        this.paymentMapper = paymentMapper;
        this.gateway = gateway;
        this.queryStateService = queryStateService;
        this.closeStateService = closeStateService;
    }

    public boolean closeExpired(Long orderId, LocalDateTime closeTime) {
        OmsOrder order = orderMapper.selectById(orderId);
        if (order == null || order.getStatus() == null || order.getStatus() != 0) return false;
        OmsPaymentRecord payment = paymentMapper.selectOne(new LambdaQueryWrapper<OmsPaymentRecord>()
                .eq(OmsPaymentRecord::getOrderId, orderId).last("limit 1"));
        if (payment == null) return closeStateService.closeWithoutChannelPayment(orderId, closeTime);
        if (paymentMapper.claimClose(payment.getId()) != 1) return false;
        try {
            AlipayPaymentGateway.QueryResult query = gateway.query(order.getOrderSn());
            if ("TRADE_SUCCESS".equals(query.tradeStatus()) || "TRADE_FINISHED".equals(query.tradeStatus())) {
                if (paymentMapper.moveClosingToQuerying(payment.getId()) == 1) {
                    queryStateService.apply(payment.getId(), query);
                }
                return false;
            }
            if ("TRADE_CLOSED".equals(query.tradeStatus())) {
                return finalizeClosed(payment.getId(), orderId, closeTime, query.rawResponse());
            }
            if (!"WAIT_BUYER_PAY".equals(query.tradeStatus()) && !"NOT_FOUND".equals(query.tradeStatus())) {
                paymentMapper.markCloseUnknown(payment.getId(), query.rawResponse());
                return false;
            }
            AlipayPaymentGateway.CloseResult close = gateway.close(order.getOrderSn());
            if (!close.closed()) {
                paymentMapper.markCloseUnknown(payment.getId(), close.rawResponse());
                return false;
            }
            return finalizeClosed(payment.getId(), orderId, closeTime, close.rawResponse());
        } catch (Exception e) {
            paymentMapper.markCloseUnknown(payment.getId(), e.getMessage());
            log.warn("Payment close uncertain, orderId={}, paymentId={}", orderId, payment.getId(), e);
            return false;
        }
    }

    private boolean finalizeClosed(Long paymentId, Long orderId, LocalDateTime closeTime, String rawResponse) {
        return closeStateService.finalizeClosed(paymentId, orderId, closeTime, rawResponse);
    }
}
