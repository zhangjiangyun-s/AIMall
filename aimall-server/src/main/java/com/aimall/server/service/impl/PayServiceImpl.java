package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsOrder;
import com.aimall.server.entity.OmsOrderItem;
import com.aimall.server.entity.OmsPaymentRecord;
import com.aimall.server.mapper.OmsOrderItemMapper;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.mapper.OmsPaymentRecordMapper;
import com.aimall.server.service.InventoryService;
import com.aimall.server.service.PayService;
import com.aimall.server.config.AlipayProperties;
import com.aimall.server.entity.PaymentCallbackEvent;
import com.aimall.server.mapper.PaymentCallbackEventMapper;
import com.aimall.server.payment.AlipaySigner;
import com.aimall.server.order.OrderStatus;
import com.aimall.server.outbox.OutboxEventType;
import com.aimall.server.money.MoneyPolicy;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import java.util.LinkedHashMap;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

@Service
public class PayServiceImpl implements PayService {

    private final OmsOrderMapper orderMapper;
    private final OmsOrderItemMapper orderItemMapper;
    private final OmsPaymentRecordMapper paymentRecordMapper;
    private final InventoryService inventoryService;
    private final AlipayProperties alipayProperties;
    private final AlipaySigner alipaySigner;
    private final PaymentCallbackEventMapper callbackEventMapper;
    private final PaymentQueryService paymentQueryService;
    private final OutboxEventService outboxEventService;
    private final PaymentEvidenceService paymentEvidenceService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MoneySnapshotService moneySnapshotService;
    private final LatePaymentStateService latePaymentStateService;

    public PayServiceImpl(OmsOrderMapper orderMapper,
                          OmsOrderItemMapper orderItemMapper,
                          OmsPaymentRecordMapper paymentRecordMapper,
                          InventoryService inventoryService) {
        this(orderMapper, orderItemMapper, paymentRecordMapper, inventoryService,
                null, null, null, null, null, null, null);
    }

    public PayServiceImpl(OmsOrderMapper orderMapper,
                          OmsOrderItemMapper orderItemMapper,
                          OmsPaymentRecordMapper paymentRecordMapper,
                          InventoryService inventoryService,
                          AlipayProperties alipayProperties,
                          AlipaySigner alipaySigner,
                          PaymentCallbackEventMapper callbackEventMapper,
                          PaymentQueryService paymentQueryService,
                          OutboxEventService outboxEventService,
                          PaymentEvidenceService paymentEvidenceService) {
        this(orderMapper, orderItemMapper, paymentRecordMapper, inventoryService, alipayProperties,
                alipaySigner, callbackEventMapper, paymentQueryService, outboxEventService,
                paymentEvidenceService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PayServiceImpl(OmsOrderMapper orderMapper,
                          OmsOrderItemMapper orderItemMapper,
                          OmsPaymentRecordMapper paymentRecordMapper,
                          InventoryService inventoryService,
                          AlipayProperties alipayProperties,
                          AlipaySigner alipaySigner,
                          PaymentCallbackEventMapper callbackEventMapper,
                          PaymentQueryService paymentQueryService,
                          OutboxEventService outboxEventService,
                          PaymentEvidenceService paymentEvidenceService,
                          LatePaymentStateService latePaymentStateService) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.paymentRecordMapper = paymentRecordMapper;
        this.inventoryService = inventoryService;
        this.alipayProperties = alipayProperties;
        this.alipaySigner = alipaySigner;
        this.callbackEventMapper = callbackEventMapper;
        this.paymentQueryService = paymentQueryService;
        this.outboxEventService = outboxEventService;
        this.paymentEvidenceService = paymentEvidenceService;
        this.latePaymentStateService = latePaymentStateService;
    }

    @Override
    public Map<String, Object> queryAlipayPayment(Long orderId, Long memberId) {
        requireAlipayEnabled();
        if (paymentQueryService == null) throw new IllegalStateException("支付查单服务未配置");
        return paymentQueryService.queryOwned(orderId, memberId);
    }

    @Override
    @Transactional
    public Map<String, Object> createAlipayPayment(Long orderId, Long memberId) {
        requireAlipayEnabled();
        OmsOrder order = requireOwnedOrder(orderId, memberId);
        if (order.getStatus() == null || order.getStatus() != OrderStatus.WAIT_PAY.code()) {
            throw new RuntimeException("当前订单状态不允许支付");
        }
        BigDecimal amount = MoneyPolicy.storage(order.getPayAmount());
        if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new RuntimeException("零元订单不应创建支付宝支付");
        OmsPaymentRecord record = paymentRecordMapper.selectOne(new LambdaQueryWrapper<OmsPaymentRecord>()
                .eq(OmsPaymentRecord::getOrderId, orderId).last("limit 1"));
        if (record == null) {
            record = new OmsPaymentRecord();
            record.setOrderId(order.getId());
            record.setOrderSn(order.getOrderSn());
            record.setPayChannel("ALIPAY_SANDBOX");
            record.setPayStatus("WAITING");
            record.setPaymentState("WAITING_PAYMENT");
            record.setAmount(amount);
            record.setPaidAmount(BigDecimal.ZERO);
            record.setRefundedAmount(BigDecimal.ZERO);
            record.setCurrencyCode(MoneyPolicy.DEFAULT_CURRENCY);
            record.setCurrencyScale(MoneyPolicy.DEFAULT_CURRENCY_SCALE);
            record.setCreateTime(LocalDateTime.now());
            record.setUpdateTime(LocalDateTime.now());
            paymentRecordMapper.insert(record);
            if (moneySnapshotService != null) moneySnapshotService.record(record);
        } else if (record.getAmount() == null || amount.compareTo(record.getAmount()) != 0) {
            throw new RuntimeException("支付金额与订单金额不一致");
        }
        if (outboxEventService != null) {
            outboxEventService.enqueue("PAYMENT", String.valueOf(record.getId()), 1L,
                    OutboxEventType.PAYMENT_CREATED, "PAYMENT_CREATED:" + record.getId(), 1,
                    Map.of("paymentId", record.getId(), "orderId", order.getId(),
                            "amount", amount, "channel", "ALIPAY_SANDBOX"));
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", alipayProperties.getAppId());
        params.put("method", "alipay.trade.page.pay");
        params.put("format", alipayProperties.getFormat());
        params.put("charset", alipayProperties.getCharset());
        params.put("sign_type", alipayProperties.getSignType());
        params.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        params.put("version", "1.0");
        params.put("notify_url", requiredNotifyUrl());
        if (alipayProperties.getReturnUrl() != null && !alipayProperties.getReturnUrl().isBlank()) {
            params.put("return_url", requiredReturnCallbackUrl());
        }
        params.put("biz_content", "{\"out_trade_no\":\"" + escapeJson(order.getOrderSn())
                + "\",\"product_code\":\"FAST_INSTANT_TRADE_PAY\",\"total_amount\":\""
                + MoneyPolicy.channel(amount, MoneyPolicy.DEFAULT_CURRENCY_SCALE).toPlainString()
                + "\",\"subject\":\"AIMall Order " + escapeJson(order.getOrderSn()) + "\"}");
        params.put("sign", alipaySigner.sign(params));
        return Map.of("orderId", orderId, "orderSn", order.getOrderSn(), "amount", amount,
                "payChannel", "ALIPAY_SANDBOX", "gatewayUrl", alipayProperties.getGatewayUrl(),
                "form", buildAutoSubmitForm(params));
    }

    @Override
    @Transactional
    public String handleAlipayNotify(Map<String, String> params) {
        requireAlipayEnabled();
        String tradeNo = params.get("trade_no");
        String outTradeNo = params.get("out_trade_no");
        String tradeStatus = params.get("trade_status");
        if (tradeNo == null || outTradeNo == null || tradeStatus == null) return "failure";
        if (!alipaySigner.verify(params, params.get("sign"))) return "failure";
        if (!alipayProperties.getAppId().equals(params.get("app_id"))
                || (alipayProperties.getSellerId() != null && !alipayProperties.getSellerId().isBlank()
                    && !alipayProperties.getSellerId().equals(params.get("seller_id")))) return "failure";
        OmsOrder order = orderMapper.selectOne(new LambdaQueryWrapper<OmsOrder>()
                .eq(OmsOrder::getOrderSn, outTradeNo).last("limit 1"));
        if (order == null) return "failure";
        BigDecimal callbackAmount;
        try { callbackAmount = new BigDecimal(params.get("total_amount")); }
        catch (Exception e) { return "failure"; }
        BigDecimal expected = order.getPayAmount() == null ? BigDecimal.ZERO : order.getPayAmount();
        if (callbackAmount.compareTo(expected) != 0) return "failure";
        String eventId = tradeNo + ":" + tradeStatus + ":" + value(params.get("gmt_payment"));
        PaymentCallbackEvent event = new PaymentCallbackEvent();
        event.setEventId(eventId);
        event.setProvider("ALIPAY_SANDBOX");
        event.setProviderReference(tradeNo);
        event.setRequestId(outTradeNo);
        String callbackTraceId = MDC.get("traceId");
        event.setTraceId(callbackTraceId == null || callbackTraceId.isBlank()
                ? UUID.randomUUID().toString() : callbackTraceId);
        event.setTenantId("default");
        event.setEventSource("ASYNC_NOTIFY");
        event.setOrderId(order.getId());
        event.setProviderStatus(tradeStatus);
        event.setAmount(callbackAmount);
        event.setSignatureValid(1);
        event.setPayloadHash(sha256(params.toString()));
        event.setRawPayload(params.toString());
        event.setProcessingState("RECEIVED");
        if (callbackEventMapper.insertIgnore(event) == 0) return "success";
        if ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)) {
            OmsPaymentRecord record = paymentRecordMapper.selectOne(new LambdaQueryWrapper<OmsPaymentRecord>()
                    .eq(OmsPaymentRecord::getOrderId, order.getId()).last("limit 1"));
            if (record == null || !expected.equals(record.getAmount())) return "failure";
            if (order.getStatus() != null && order.getStatus() == OrderStatus.CLOSED.code()) {
                if (latePaymentStateService == null) return "failure";
                latePaymentStateService.recordDetected(order, record, tradeNo, callbackAmount, params.toString());
                event.setProcessingState("PROCESSED");
                event.setProcessedAt(LocalDateTime.now());
                callbackEventMapper.updateById(event);
                return "success";
            }
            if (!"PAID".equals(record.getPaymentState())) {
                List<OmsOrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OmsOrderItem>()
                        .eq(OmsOrderItem::getOrderId, order.getId()));
                inventoryService.deductForOrderItems(items);
                if (orderMapper.markPaid(order.getId(), order.getMemberId(), 1, LocalDateTime.now()) != 1) return "failure";
                if (paymentRecordMapper.markAlipayPaid(order.getId(), expected, tradeNo, LocalDateTime.now(), params.toString()) != 1) return "failure";
                recordMoneySnapshots(order.getId(), record.getId());
                if (outboxEventService != null) {
                    outboxEventService.enqueue("PAYMENT", String.valueOf(record.getId()), 2L,
                            OutboxEventType.PAYMENT_SUCCEEDED, "PAYMENT_SUCCEEDED:" + record.getId(), 1,
                            Map.of("paymentId", record.getId(), "orderId", order.getId(), "transactionNo", tradeNo));
                }
            }
        }
        event.setProcessingState("PROCESSED");
        event.setProcessedAt(LocalDateTime.now());
        callbackEventMapper.updateById(event);
        return "success";
    }

    @Override
    @Transactional
    public String handleAlipayReturn(Map<String, String> params) {
        requireAlipayEnabled();
        String orderSn = params.get("out_trade_no");
        String tradeNo = params.get("trade_no");
        if (orderSn == null || tradeNo == null || !alipaySigner.verify(params, params.get("sign"))) {
            throw new IllegalArgumentException("支付宝同步返回验签失败");
        }
        if (!alipayProperties.getAppId().equals(params.get("app_id"))) {
            throw new IllegalArgumentException("支付宝同步返回应用不匹配");
        }
        String sellerId = params.get("seller_id");
        if (alipayProperties.getSellerId() != null && !alipayProperties.getSellerId().isBlank()
                && sellerId != null && !sellerId.isBlank()
                && !alipayProperties.getSellerId().equals(sellerId)) {
            throw new IllegalArgumentException("支付宝同步返回商户不匹配");
        }
        OmsOrder order = orderMapper.selectOne(new LambdaQueryWrapper<OmsOrder>()
                .eq(OmsOrder::getOrderSn, orderSn).last("limit 1"));
        if (order == null) throw new IllegalArgumentException("支付宝同步返回订单不存在");
        BigDecimal amount;
        try { amount = new BigDecimal(params.get("total_amount")); }
        catch (Exception exception) { throw new IllegalArgumentException("支付宝同步返回金额无效"); }
        if (order.getPayAmount() == null || amount.compareTo(order.getPayAmount()) != 0) {
            throw new IllegalArgumentException("支付宝同步返回金额不匹配");
        }
        if (paymentEvidenceService != null) {
            paymentEvidenceService.recordVerifiedReturn(order.getId(), orderSn, tradeNo, amount, params.toString());
        }
        String frontend = alipayProperties.getReturnUrl();
        String separator = frontend.contains("?") ? "&" : "?";
        return frontend + separator + "alipayReturn=verified&orderSn="
                + URLEncoder.encode(orderSn, StandardCharsets.UTF_8);
    }

    @Override
    @Transactional
    public void simulatePay(Long orderId, Long memberId) {
        OmsOrder order = requireOwnedOrder(orderId, memberId);
        if (order.getStatus() == null || order.getStatus() != OrderStatus.WAIT_PAY.code()) {
            throw new RuntimeException("当前订单状态不可支付");
        }

        List<OmsOrderItem> orderItems = orderItemMapper.selectList(
                new LambdaQueryWrapper<OmsOrderItem>().eq(OmsOrderItem::getOrderId, orderId)
        );
        inventoryService.deductForOrderItems(orderItems);

        LocalDateTime now = LocalDateTime.now();
        if (orderMapper.markPaid(orderId, memberId, 1, now) != 1) {
            throw new RuntimeException("订单状态已变化，支付失败");
        }

        OmsPaymentRecord record = paymentRecordMapper.selectOne(
                new LambdaQueryWrapper<OmsPaymentRecord>().eq(OmsPaymentRecord::getOrderId, orderId).last("limit 1")
        );
        if (record == null) {
            record = new OmsPaymentRecord();
            record.setOrderId(order.getId());
            record.setOrderSn(order.getOrderSn());
            record.setPayChannel("SIMULATE");
            record.setAmount(MoneyPolicy.storage(order.getPayAmount()));
            record.setCurrencyCode(MoneyPolicy.DEFAULT_CURRENCY);
            record.setCurrencyScale(MoneyPolicy.DEFAULT_CURRENCY_SCALE);
        }
        record.setPayStatus("PAID");
        record.setPaymentState("PAID");
        record.setPaidAmount(record.getAmount());
        record.setTransactionNo(generateTransactionNo());
        record.setPayTime(now);
        record.setCallbackTime(now);
        record.setRawCallback("{\"channel\":\"SIMULATE\",\"result\":\"SUCCESS\"}");

        if (record.getId() == null) {
            paymentRecordMapper.insert(record);
        } else {
            paymentRecordMapper.updateById(record);
        }
        if (moneySnapshotService != null) moneySnapshotService.record(record);
    }

    @Override
    public Map<String, Object> getPayStatus(Long orderId, Long memberId) {
        OmsOrder order = requireOwnedOrder(orderId, memberId);
        OmsPaymentRecord record = paymentRecordMapper.selectOne(
                new LambdaQueryWrapper<OmsPaymentRecord>().eq(OmsPaymentRecord::getOrderId, orderId).last("limit 1")
        );
        return Map.of(
                "orderId", order.getId(),
                "orderSn", order.getOrderSn(),
                "orderStatus", order.getStatus(),
                "payStatus", record == null ? "WAIT_PAY" : record.getPayStatus(),
                "payChannel", record == null ? "SIMULATE" : record.getPayChannel(),
                "amount", order.getPayAmount() == null ? BigDecimal.ZERO : order.getPayAmount(),
                "transactionNo", record == null ? "" : valueOrEmpty(record.getTransactionNo()),
                "payTime", record == null || record.getPayTime() == null ? "" : formatTime(record.getPayTime())
        );
    }

    private void recordMoneySnapshots(Long orderId, Long paymentId) {
        if (moneySnapshotService == null) return;
        OmsOrder currentOrder = orderMapper.selectById(orderId);
        OmsPaymentRecord currentPayment = paymentRecordMapper.selectById(paymentId);
        if (currentOrder != null) moneySnapshotService.record(currentOrder);
        if (currentPayment != null) moneySnapshotService.record(currentPayment);
    }

    private OmsOrder requireOwnedOrder(Long orderId, Long memberId) {
        OmsOrder order = orderMapper.selectById(orderId);
        if (order == null || order.getDeleteStatus() == 1) {
            throw new RuntimeException("订单不存在");
        }
        if (!memberId.equals(order.getMemberId())) {
            throw new RuntimeException("订单不属于当前用户");
        }
        return order;
    }

    private String generateTransactionNo() {
        return "PAY" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private String formatTime(LocalDateTime time) {
        return time.toString().replace("T", " ");
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private void requireAlipayEnabled() {
        if (alipayProperties == null || !alipayProperties.isEnabled() || alipaySigner == null) {
            throw new IllegalStateException("支付宝支付未配置");
        }
    }

    private String requiredNotifyUrl() {
        if (alipayProperties.getNotifyBaseUrl() == null || alipayProperties.getNotifyBaseUrl().isBlank()) {
            throw new IllegalStateException("ALIPAY_NOTIFY_BASE_URL 未配置");
        }
        return alipayProperties.getNotifyBaseUrl().replaceAll("/$", "") + "/api/pay/alipay/notify";
    }

    private String requiredReturnCallbackUrl() {
        if (alipayProperties.getNotifyBaseUrl() == null || alipayProperties.getNotifyBaseUrl().isBlank()) {
            throw new IllegalStateException("ALIPAY_NOTIFY_BASE_URL 未配置");
        }
        return alipayProperties.getNotifyBaseUrl().replaceAll("/$", "") + "/api/pay/alipay/return";
    }

    private String buildAutoSubmitForm(Map<String, String> params) {
        String gateway = alipayProperties.getGatewayUrl();
        String separator = gateway.contains("?") ? "&" : "?";
        String action = gateway + separator + "charset="
                + URLEncoder.encode(alipayProperties.getCharset(), StandardCharsets.UTF_8);
        StringBuilder html = new StringBuilder("<form id=\"alipay\" method=\"post\" action=\"")
                .append(escapeHtml(action)).append("\">");
        params.forEach((key, value) -> html.append("<input type=\"hidden\" name=\"")
                .append(escapeHtml(key)).append("\" value=\"").append(escapeHtml(value)).append("\">"));
        return html.append("</form><script>document.getElementById('alipay').submit()</script>").toString();
    }
    private String escapeJson(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\""); }
    private String escapeHtml(String value) { return value == null ? "" : value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;"); }
    private String value(String value) { return value == null ? "" : value; }
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
