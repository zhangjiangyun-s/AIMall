package com.aimall.server.payment;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class AlipayPaymentGateway {
    private final AlipayApiClient client;

    public AlipayPaymentGateway(AlipayApiClient client) {
        this.client = client;
    }

    public QueryResult query(String orderSn) {
        JsonNode response = client.execute(
                "alipay.trade.query",
                "{\"out_trade_no\":\"" + escape(orderSn) + "\"}",
                "alipay_trade_query_response"
        );
        String code = response.path("code").asText();
        if ("40004".equals(code) && "ACQ.TRADE_NOT_EXIST".equals(response.path("sub_code").asText())) {
            return new QueryResult("NOT_FOUND", null, null, null, response.toString());
        }
        if (!"10000".equals(code)) {
            throw new IllegalStateException("支付宝查单失败: " + response.path("sub_msg").asText(response.path("msg").asText("unknown")));
        }
        return new QueryResult(
                response.path("trade_status").asText(),
                response.path("trade_no").asText(null),
                decimal(response.path("total_amount").asText(null)),
                response.path("buyer_user_id").asText(null),
                response.toString()
        );
    }

    public CloseResult close(String orderSn) {
        JsonNode response = client.execute(
                "alipay.trade.close",
                "{\"out_trade_no\":\"" + escape(orderSn) + "\"}",
                "alipay_trade_close_response"
        );
        String code = response.path("code").asText();
        if ("10000".equals(code)) {
            return new CloseResult(true, response.path("trade_no").asText(null), response.toString());
        }
        if ("40004".equals(code) && "ACQ.TRADE_NOT_EXIST".equals(response.path("sub_code").asText())) {
            return new CloseResult(true, null, response.toString());
        }
        throw new IllegalStateException("支付宝关单失败: "
                + response.path("sub_msg").asText(response.path("msg").asText("unknown")));
    }

    private BigDecimal decimal(String value) { return value == null || value.isBlank() ? null : new BigDecimal(value); }
    private String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }

    public record QueryResult(String tradeStatus, String tradeNo, BigDecimal totalAmount,
                              String buyerUserId, String rawResponse) {}
    public record CloseResult(boolean closed, String tradeNo, String rawResponse) {}
}
