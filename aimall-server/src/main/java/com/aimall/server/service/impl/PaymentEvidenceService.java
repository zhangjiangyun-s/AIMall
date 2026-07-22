package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsPaymentRecord;
import com.aimall.server.entity.PaymentCallbackEvent;
import com.aimall.server.mapper.PaymentCallbackEventMapper;
import com.aimall.server.payment.AlipayPaymentGateway;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class PaymentEvidenceService {
    private final PaymentCallbackEventMapper mapper;

    public PaymentEvidenceService(PaymentCallbackEventMapper mapper) {
        this.mapper = mapper;
    }

    public void recordQuery(OmsPaymentRecord payment, AlipayPaymentGateway.QueryResult result) {
        if (payment == null || result == null) return;
        PaymentCallbackEvent evidence = new PaymentCallbackEvent();
        evidence.setEventId("QUERY:" + sha256(payment.getOrderSn() + ":"
                + safe(result.tradeStatus()) + ":" + safe(result.tradeNo())));
        evidence.setProvider("ALIPAY_SANDBOX");
        evidence.setProviderReference(result.tradeNo());
        evidence.setRequestId(payment.getOrderSn());
        evidence.setEventSource("ACTIVE_QUERY");
        evidence.setOrderId(payment.getOrderId());
        evidence.setProviderStatus(result.tradeStatus());
        evidence.setAmount(result.totalAmount());
        evidence.setSignatureValid(1);
        evidence.setRawPayload(safe(result.rawResponse()));
        evidence.setPayloadHash(sha256(evidence.getRawPayload()));
        evidence.setProcessingState("PROCESSED");
        mapper.insertIgnore(evidence);
    }

    public void recordVerifiedReturn(Long orderId, String orderSn, String tradeNo,
                                     java.math.BigDecimal amount, String rawPayload) {
        PaymentCallbackEvent evidence = new PaymentCallbackEvent();
        evidence.setEventId("RETURN:" + sha256(orderSn + ":" + safe(tradeNo)));
        evidence.setProvider("ALIPAY_SANDBOX");
        evidence.setProviderReference(tradeNo);
        evidence.setRequestId(orderSn);
        evidence.setEventSource("SYNC_RETURN");
        evidence.setOrderId(orderId);
        evidence.setProviderStatus("RETURN_VERIFIED");
        evidence.setAmount(amount);
        evidence.setSignatureValid(1);
        evidence.setRawPayload(safe(rawPayload));
        evidence.setPayloadHash(sha256(evidence.getRawPayload()));
        evidence.setProcessingState("PROCESSED");
        mapper.insertIgnore(evidence);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("支付凭证摘要计算失败", exception);
        }
    }

    private String safe(String value) { return value == null ? "" : value; }
}
