package com.aimall.server.integration;

import com.aimall.server.config.AlipayProperties;
import com.aimall.server.payment.AlipayApiClient;
import com.aimall.server.payment.AlipayPaymentGateway;
import com.aimall.server.payment.AlipaySigner;
import com.aimall.server.service.RefundGateway;
import com.aimall.server.service.impl.AlipayRefundGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "aimall.alipay.live", matches = "true")
class AlipaySandboxLiveIntegrationTest {
    private static AlipayPaymentGateway paymentGateway;
    private static AlipayRefundGateway refundGateway;

    @BeforeAll
    static void setUp() {
        AlipayProperties properties = new AlipayProperties();
        properties.setEnabled(true);
        properties.setAppId(required("ALIPAY_APP_ID"));
        properties.setSellerId(required("ALIPAY_SELLER_ID"));
        properties.setPrivateKeyFile(required("ALIPAY_PRIVATE_KEY_FILE"));
        properties.setPublicKeyFile(required("ALIPAY_PUBLIC_KEY_FILE"));
        properties.setGatewayUrl(required("ALIPAY_GATEWAY_URL"));
        properties.setSignType(value("ALIPAY_SIGN_TYPE", "RSA2"));
        properties.setCharset(value("ALIPAY_CHARSET", "UTF-8"));
        properties.setFormat(value("ALIPAY_FORMAT", "json"));
        AlipayApiClient client = new AlipayApiClient(properties, new AlipaySigner(properties), new ObjectMapper());
        paymentGateway = new AlipayPaymentGateway(client);
        refundGateway = new AlipayRefundGateway(client);
    }

    @Test
    void paidTradeCanBeQueriedAndMatchesTheLocalAmount() {
        AlipayPaymentGateway.QueryResult result = paymentGateway.query(required("ALIPAY_E2E_PAID_ORDER_SN"));

        assertTrue("TRADE_SUCCESS".equals(result.tradeStatus()) || "TRADE_FINISHED".equals(result.tradeStatus()));
        assertEquals(new BigDecimal(required("ALIPAY_E2E_PAID_AMOUNT")), result.totalAmount());
        assertNotNull(result.tradeNo());
    }

    @Test
    void completedRefundCanBeQueriedByItsIdempotencyKey() {
        BigDecimal expected = new BigDecimal(required("ALIPAY_E2E_REFUND_AMOUNT"));
        RefundGateway.QueryRefundResult result = refundGateway.queryRefund(
                required("ALIPAY_E2E_REFUND_REQUEST_ID"),
                required("ALIPAY_E2E_REFUND_ORDER_SN"),
                expected
        );

        assertEquals("SUCCEEDED", result.status());
        assertEquals(expected, result.refundAmount());
        assertNotNull(result.transactionNo());
    }

    @Test
    void closingANonexistentSandboxTradeIsIdempotentlySuccessful() throws InterruptedException {
        AlipayPaymentGateway.CloseResult result = null;
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                result = paymentGateway.close(required("ALIPAY_E2E_CLOSED_ORDER_SN"));
                break;
            } catch (RuntimeException exception) {
                lastFailure = exception;
                if (attempt < 3) Thread.sleep(1000L * attempt);
            }
        }

        if (result == null) throw lastFailure;
        assertTrue(result.closed());
    }

    private static String required(String name) {
        String result = System.getenv(name);
        if (result == null || result.isBlank()) throw new IllegalStateException(name + " is required");
        return result;
    }

    private static String value(String name, String fallback) {
        String result = System.getenv(name);
        return result == null || result.isBlank() ? fallback : result;
    }
}
