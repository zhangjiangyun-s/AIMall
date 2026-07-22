package com.aimall.server.service.impl;

import com.aimall.server.service.RefundGateway;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
@ConditionalOnProperty(name = "aimall.payment.provider", havingValue = "SIMULATE", matchIfMissing = true)
public class SimulatedRefundGateway implements RefundGateway {

    @Override
    public String channel() {
        return "SIMULATE";
    }

    @Override
    public RefundResult refund(String requestId, String orderSn, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("退款金额必须大于 0");
        }
        return new RefundResult("REF" + stableHash(requestId).substring(0, 24).toUpperCase());
    }

    @Override
    public QueryRefundResult queryRefund(String requestId, String orderSn, BigDecimal expectedAmount) {
        return new QueryRefundResult("SUCCEEDED",
                "REF" + stableHash(requestId).substring(0, 24).toUpperCase(), expectedAmount,
                "{\"channel\":\"SIMULATE\",\"status\":\"SUCCEEDED\"}");
    }

    private String stableHash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("退款幂等键计算失败", exception);
        }
    }
}
