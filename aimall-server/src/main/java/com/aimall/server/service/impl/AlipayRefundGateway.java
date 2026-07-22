package com.aimall.server.service.impl;

import com.aimall.server.payment.AlipayApiClient;
import com.aimall.server.service.RefundGateway;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import com.aimall.server.money.MoneyPolicy;

@Component
@ConditionalOnProperty(name = "aimall.payment.provider", havingValue = "ALIPAY_SANDBOX")
public class AlipayRefundGateway implements RefundGateway {
    private final AlipayApiClient client;

    public AlipayRefundGateway(AlipayApiClient client) {
        this.client = client;
    }

    @Override
    public String channel() {
        return "ALIPAY_SANDBOX";
    }

    @Override
    public RefundResult refund(String requestId, String orderSn, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("退款金额必须大于 0");
        JsonNode response = client.execute("alipay.trade.refund",
                "{\"out_trade_no\":\"" + json(orderSn) + "\",\"refund_amount\":\""
                        + MoneyPolicy.channel(amount, MoneyPolicy.DEFAULT_CURRENCY_SCALE).toPlainString()
                        + "\",\"out_request_no\":\"" + json(requestId) + "\"}",
                "alipay_trade_refund_response");
        if (!"10000".equals(response.path("code").asText())
                || !"Y".equalsIgnoreCase(response.path("fund_change").asText("Y"))) {
            throw new IllegalStateException("支付宝退款失败: "
                    + response.path("sub_msg").asText(response.path("msg").asText("unknown")));
        }
        String tradeNo = response.path("trade_no").asText("");
        return new RefundResult(tradeNo.isBlank() ? "ALIPAY-REFUND-" + requestId : tradeNo);
    }

    @Override
    public QueryRefundResult queryRefund(String requestId, String orderSn, BigDecimal expectedAmount) {
        JsonNode response = client.execute("alipay.trade.fastpay.refund.query",
                "{\"out_trade_no\":\"" + json(orderSn) + "\",\"out_request_no\":\"" + json(requestId) + "\"}",
                "alipay_trade_fastpay_refund_query_response");
        String code = response.path("code").asText();
        String subCode = response.path("sub_code").asText();
        if ("40004".equals(code)
                && ("ACQ.TRADE_NOT_EXIST".equals(subCode) || "ACQ.REFUND_NOT_EXIST".equals(subCode))) {
            return new QueryRefundResult("NOT_FOUND", null, null, response.toString());
        }
        if (!"10000".equals(code)) {
            throw new IllegalStateException("支付宝退款查单失败: "
                    + response.path("sub_msg").asText(response.path("msg").asText("unknown")));
        }
        BigDecimal refundAmount = decimal(response.path("refund_amount").asText(null));
        String status = refundAmount != null && expectedAmount != null && refundAmount.compareTo(expectedAmount) == 0
                ? "SUCCEEDED" : "AMOUNT_MISMATCH";
        return new QueryRefundResult(status, response.path("trade_no").asText(null), refundAmount, response.toString());
    }

    private BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value);
    }

    private String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
