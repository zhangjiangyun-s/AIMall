package com.aimall.server.service.impl;

import com.aimall.server.mapper.OmsPaymentRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class PaymentCloseStateService {
    private final OmsPaymentRecordMapper paymentMapper;
    private final OrderTimeoutCloser timeoutCloser;

    public PaymentCloseStateService(OmsPaymentRecordMapper paymentMapper, OrderTimeoutCloser timeoutCloser) {
        this.paymentMapper = paymentMapper;
        this.timeoutCloser = timeoutCloser;
    }

    @Transactional
    public boolean finalizeClosed(Long paymentId, Long orderId, LocalDateTime closeTime, String rawResponse) {
        if (paymentMapper.markChannelClosed(paymentId, rawResponse) != 1) return false;
        if (!timeoutCloser.closeAfterChannelConfirmed(orderId, closeTime)) {
            throw new IllegalStateException("渠道关单成功但本地订单关闭失败");
        }
        return true;
    }

    @Transactional
    public boolean closeWithoutChannelPayment(Long orderId, LocalDateTime closeTime) {
        return timeoutCloser.closeAfterChannelConfirmed(orderId, closeTime);
    }
}
