package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsCartItem;
import com.aimall.server.entity.OmsOrder;
import com.aimall.server.entity.OmsOrderItem;
import com.aimall.server.entity.OmsOrderRequest;
import com.aimall.server.entity.OmsPaymentRecord;
import com.aimall.server.entity.UmsMember;
import com.aimall.server.entity.UmsMemberAddress;
import com.aimall.server.mapper.OmsCartItemMapper;
import com.aimall.server.mapper.OmsOrderItemMapper;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.mapper.OmsOrderRequestMapper;
import com.aimall.server.mapper.OmsPaymentRecordMapper;
import com.aimall.server.mapper.UmsMemberAddressMapper;
import com.aimall.server.mapper.UmsMemberMapper;
import com.aimall.server.service.InventoryService;
import com.aimall.server.service.OrderService;
import com.aimall.server.service.CartPricingService;
import com.aimall.server.order.OrderStatus;
import com.aimall.server.outbox.OutboxEventType;
import com.aimall.server.money.MoneyPolicy;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.dao.PessimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class OrderServiceImpl implements OrderService {

    private static final String INVENTORY_NOT_RESERVED = "NOT_RESERVED";
    private static final String INVENTORY_RESERVED = "RESERVED";
    private static final String INVENTORY_DEDUCTED = "DEDUCTED";
    private static final String INVENTORY_RELEASED = "RELEASED";

    private final OmsOrderMapper orderMapper;
    private final OmsOrderItemMapper orderItemMapper;
    private final OmsCartItemMapper cartItemMapper;
    private final InventoryService inventoryService;
    private final UmsMemberMapper memberMapper;
    private final UmsMemberAddressMapper addressMapper;
    private final CouponServiceImpl couponService;
    private final CartPricingService cartPricingService;
    private final OmsOrderRequestMapper orderRequestMapper;
    private final OmsPaymentRecordMapper paymentRecordMapper;
    private final int paymentTimeoutMinutes;
    private final PaymentCloseService paymentCloseService;
    private final OutboxEventService outboxEventService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MoneySnapshotService moneySnapshotService;

    public OrderServiceImpl(
            OmsOrderMapper orderMapper,
            OmsOrderItemMapper orderItemMapper,
            OmsCartItemMapper cartItemMapper,
            InventoryService inventoryService,
            UmsMemberMapper memberMapper,
            UmsMemberAddressMapper addressMapper,
            CouponServiceImpl couponService,
            CartPricingService cartPricingService,
            OmsOrderRequestMapper orderRequestMapper,
            OmsPaymentRecordMapper paymentRecordMapper,
            @Value("${aimall.order.payment-timeout-minutes:30}") int paymentTimeoutMinutes
    ) {
        this(orderMapper, orderItemMapper, cartItemMapper, inventoryService, memberMapper, addressMapper,
                couponService, cartPricingService, orderRequestMapper, paymentRecordMapper,
                paymentTimeoutMinutes, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OrderServiceImpl(
            OmsOrderMapper orderMapper,
            OmsOrderItemMapper orderItemMapper,
            OmsCartItemMapper cartItemMapper,
            InventoryService inventoryService,
            UmsMemberMapper memberMapper,
            UmsMemberAddressMapper addressMapper,
            CouponServiceImpl couponService,
            CartPricingService cartPricingService,
            OmsOrderRequestMapper orderRequestMapper,
            OmsPaymentRecordMapper paymentRecordMapper,
            @Value("${aimall.order.payment-timeout-minutes:30}") int paymentTimeoutMinutes,
            PaymentCloseService paymentCloseService,
            OutboxEventService outboxEventService
    ) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.cartItemMapper = cartItemMapper;
        this.inventoryService = inventoryService;
        this.memberMapper = memberMapper;
        this.addressMapper = addressMapper;
        this.couponService = couponService;
        this.cartPricingService = cartPricingService;
        this.orderRequestMapper = orderRequestMapper;
        this.paymentRecordMapper = paymentRecordMapper;
        this.paymentTimeoutMinutes = Math.max(1, paymentTimeoutMinutes);
        this.paymentCloseService = paymentCloseService;
        this.outboxEventService = outboxEventService;
    }

    @Override
    @Transactional
    @Retryable(retryFor = PessimisticLockingFailureException.class, maxAttempts = 3,
            backoff = @Backoff(delay = 50, maxDelay = 500, multiplier = 2))
    public OmsOrder create(Long memberId, String requestId, Long addressId, Long memberCouponId, List<Long> cartItemIds) {
        String normalizedRequestId = normalizeRequestId(requestId);
        if (orderRequestMapper.reserve(memberId, normalizedRequestId) == 0) {
            OmsOrderRequest existingRequest = orderRequestMapper.selectOne(
                    new LambdaQueryWrapper<OmsOrderRequest>()
                            .eq(OmsOrderRequest::getMemberId, memberId)
                            .eq(OmsOrderRequest::getRequestId, normalizedRequestId)
                            .last("limit 1")
            );
            if (existingRequest != null
                    && "SUCCEEDED".equals(existingRequest.getStatus())
                    && existingRequest.getOrderId() != null) {
                OmsOrder existingOrder = orderMapper.selectById(existingRequest.getOrderId());
                if (existingOrder != null && memberId.equals(existingOrder.getMemberId())) {
                    return existingOrder;
                }
            }
            throw new RuntimeException("订单请求正在处理中，请勿重复提交");
        }
        if (cartItemIds == null || cartItemIds.isEmpty()) {
            throw new RuntimeException("请选择购物车商品");
        }
        CartPricingService.PriceQuote priceQuote = cartPricingService.quote(memberId, cartItemIds);
        List<OmsCartItem> cartItems = priceQuote.cartItems();
        BigDecimal totalAmount = MoneyPolicy.storage(priceQuote.goodsAmount());

        UmsMemberAddress address = addressMapper.selectById(addressId);
        if (address == null || !memberId.equals(address.getMemberId())) {
            throw new RuntimeException("收货地址不存在");
        }

        inventoryService.reserveForCartItems(cartItems);

        BigDecimal couponAmount = MoneyPolicy.storage(couponService.resolveCouponAmount(memberId, memberCouponId, totalAmount, priceQuote.items()));
        BigDecimal payAmount = MoneyPolicy.storage(totalAmount.subtract(couponAmount));
        if (payAmount.compareTo(BigDecimal.ZERO) < 0) {
            payAmount = MoneyPolicy.storage(BigDecimal.ZERO);
        }
        boolean zeroAmountOrder = payAmount.compareTo(BigDecimal.ZERO) == 0;
        LocalDateTime now = LocalDateTime.now();

        UmsMember member = memberMapper.selectById(memberId);
        OmsOrder order = new OmsOrder();
        order.setMemberId(memberId);
        order.setMemberUsername(member == null ? "user-" + memberId : member.getUsername());
        order.setOrderSn(generateOrderSn());
        order.setStatus(zeroAmountOrder ? OrderStatus.WAIT_SHIP.code() : OrderStatus.WAIT_PAY.code());
        order.setPayType(0);
        order.setSourceType(1);
        order.setOrderType(0);
        order.setConfirmStatus(0);
        order.setDeleteStatus(0);
        order.setPromotionInfo(memberCouponId == null ? "普通下单" : "使用优惠券结算");
        order.setFreightAmount(MoneyPolicy.storage(BigDecimal.ZERO));
        order.setPromotionAmount(MoneyPolicy.storage(BigDecimal.ZERO));
        order.setCouponAmount(couponAmount);
        order.setDiscountAmount(couponAmount);
        order.setTotalAmount(totalAmount);
        order.setPayAmount(payAmount);
        order.setReceiverName(address.getName());
        order.setReceiverPhone(address.getPhone());
        order.setReceiverProvince(address.getProvince());
        order.setReceiverCity(address.getCity());
        order.setReceiverRegion(address.getRegion());
        order.setReceiverDetailAddress(address.getDetailAddress());
        order.setCreateTime(now);
        order.setModifyTime(now);
        order.setInventoryReservationStatus(INVENTORY_RESERVED);
        order.setCurrencyCode(MoneyPolicy.DEFAULT_CURRENCY);
        order.setCurrencyScale(MoneyPolicy.DEFAULT_CURRENCY_SCALE);
        if (zeroAmountOrder) {
            order.setPaymentTime(now);
        } else {
            order.setExpireTime(now.plusMinutes(paymentTimeoutMinutes));
        }
        orderMapper.insert(order);
        if (moneySnapshotService != null) moneySnapshotService.record(order);

        List<BigDecimal> couponAllocations = MoneyPolicy.allocate(couponAmount,
                priceQuote.items().stream().map(CartPricingService.PricedCartItem::lineAmount).toList());
        List<OmsOrderItem> createdOrderItems = new ArrayList<>();
        for (int i = 0; i < cartItems.size(); i++) {
            CartPricingService.PricedCartItem pricedItem = priceQuote.items().get(i);
            OmsCartItem item = pricedItem.cartItem();
            BigDecimal itemTotal = MoneyPolicy.storage(pricedItem.lineAmount());
            BigDecimal itemCouponAmount = couponAllocations.get(i);

            OmsOrderItem orderItem = new OmsOrderItem();
            orderItem.setOrderId(order.getId());
            orderItem.setOrderSn(order.getOrderSn());
            orderItem.setProductId(item.getProductId());
            orderItem.setProductPic(pricedItem.sku() != null && pricedItem.sku().getPic() != null
                    ? pricedItem.sku().getPic() : pricedItem.product().getPic());
            orderItem.setProductName(pricedItem.product().getName());
            orderItem.setProductBrand(pricedItem.product().getBrandName());
            orderItem.setProductSn(pricedItem.product().getProductSn());
            orderItem.setProductPrice(MoneyPolicy.storage(pricedItem.unitPrice()));
            orderItem.setProductQuantity(item.getQuantity());
            orderItem.setProductSkuId(item.getProductSkuId());
            orderItem.setProductSkuCode(pricedItem.sku() == null ? null : pricedItem.sku().getSkuCode());
            orderItem.setProductCategoryId(pricedItem.product().getCategoryId());
            orderItem.setPromotionName(pricedItem.priceRuleName() != null
                    ? pricedItem.priceRuleName()
                    : (memberCouponId == null ? null : "优惠券抵扣"));
            BigDecimal baseUnitPrice = pricedItem.sku() == null
                    ? (pricedItem.product().getPromotionPrice() == null
                        ? pricedItem.product().getPrice() : pricedItem.product().getPromotionPrice())
                    : (pricedItem.sku().getPromotionPrice() == null
                        ? pricedItem.sku().getPrice() : pricedItem.sku().getPromotionPrice());
            orderItem.setPromotionAmount(MoneyPolicy.storage(baseUnitPrice.subtract(pricedItem.unitPrice())
                    .max(BigDecimal.ZERO).multiply(BigDecimal.valueOf(item.getQuantity()))));
            orderItem.setCouponAmount(itemCouponAmount);
            orderItem.setRealAmount(MoneyPolicy.storage(itemTotal.subtract(itemCouponAmount)));
            orderItem.setProductAttr(pricedItem.sku() == null ? null : pricedItem.sku().getSpData());
            orderItemMapper.insert(orderItem);
            if (moneySnapshotService != null) moneySnapshotService.record(orderItem);
            createdOrderItems.add(orderItem);

            item.setDeleteStatus(1);
            item.setModifyDate(LocalDateTime.now());
            cartItemMapper.updateById(item);
        }

        couponService.markCouponUsed(memberId, memberCouponId, order.getId(), order.getOrderSn(), couponAmount);
        if (zeroAmountOrder) {
            inventoryService.deductForOrderItems(createdOrderItems);
            if (orderMapper.markInventoryDeducted(order.getId(), now) != 1) {
                throw new RuntimeException("订单库存状态更新失败");
            }
            order.setInventoryReservationStatus(INVENTORY_DEDUCTED);
            OmsPaymentRecord paymentRecord = new OmsPaymentRecord();
            paymentRecord.setOrderId(order.getId());
            paymentRecord.setOrderSn(order.getOrderSn());
            paymentRecord.setPayChannel("ZERO_AMOUNT");
            paymentRecord.setPayStatus("PAID");
            paymentRecord.setPaymentState("PAID");
            paymentRecord.setAmount(MoneyPolicy.storage(BigDecimal.ZERO));
            paymentRecord.setPaidAmount(MoneyPolicy.storage(BigDecimal.ZERO));
            paymentRecord.setRefundedAmount(MoneyPolicy.storage(BigDecimal.ZERO));
            paymentRecord.setCurrencyCode(MoneyPolicy.DEFAULT_CURRENCY);
            paymentRecord.setCurrencyScale(MoneyPolicy.DEFAULT_CURRENCY_SCALE);
            paymentRecord.setTransactionNo("ZERO-" + order.getOrderSn());
            paymentRecord.setPayTime(now);
            paymentRecord.setCallbackTime(now);
            paymentRecord.setRawCallback("{\"channel\":\"ZERO_AMOUNT\",\"result\":\"SUCCESS\"}");
            paymentRecordMapper.insert(paymentRecord);
            if (moneySnapshotService != null) moneySnapshotService.record(paymentRecord);
            if (outboxEventService != null) {
                outboxEventService.enqueue("PAYMENT", String.valueOf(paymentRecord.getId()), 1L,
                        OutboxEventType.PAYMENT_CREATED, "PAYMENT_CREATED:" + paymentRecord.getId(), 1,
                        java.util.Map.of("paymentId", paymentRecord.getId(), "orderId", order.getId(),
                                "amount", BigDecimal.ZERO, "channel", "ZERO_AMOUNT"));
                outboxEventService.enqueue("PAYMENT", String.valueOf(paymentRecord.getId()), 2L,
                        OutboxEventType.PAYMENT_SUCCEEDED, "PAYMENT_SUCCEEDED:" + paymentRecord.getId(), 1,
                        java.util.Map.of("paymentId", paymentRecord.getId(), "orderId", order.getId(),
                                "transactionNo", paymentRecord.getTransactionNo()));
            }
        }
        if (orderRequestMapper.markSucceeded(memberId, normalizedRequestId, order.getId(), order.getOrderSn()) != 1) {
            throw new RuntimeException("订单幂等记录更新失败");
        }
        if (outboxEventService != null) {
            outboxEventService.enqueue("ORDER", String.valueOf(order.getId()), 1L,
                    OutboxEventType.ORDER_CREATED, "ORDER_CREATED:" + order.getId(), 1,
                    java.util.Map.of("orderId", order.getId(), "orderSn", order.getOrderSn(),
                            "memberId", memberId, "payAmount", payAmount));
        }
        return order;
    }

    @Override
    public List<OmsOrder> listByMember(Long memberId) {
        return orderMapper.selectList(
                new LambdaQueryWrapper<OmsOrder>()
                        .eq(OmsOrder::getMemberId, memberId)
                        .eq(OmsOrder::getDeleteStatus, 0)
                        .orderByDesc(OmsOrder::getId)
        );
    }

    @Override
    public OmsOrder getById(Long id) {
        return orderMapper.selectById(id);
    }

    @Override
    public void cancel(Long memberId, Long orderId) {
        OmsOrder order = requireOwnedOrder(memberId, orderId);
        if (order.getStatus() == null || order.getStatus() != OrderStatus.WAIT_PAY.code()) {
            throw new RuntimeException("当前订单不可取消");
        }
        if (paymentCloseService != null) {
            LocalDateTime requestTime = LocalDateTime.now();
            if (orderMapper.requestCancellation(orderId, memberId, requestTime) != 1) {
                throw new RuntimeException("订单状态已变化，取消失败");
            }
            boolean closed = paymentCloseService.closeExpired(orderId, requestTime);
            if (!closed && outboxEventService != null) {
                outboxEventService.enqueue("ORDER", String.valueOf(orderId), "PAYMENT_CLOSE_REQUESTED",
                        "USER_CANCEL_CLOSE:" + orderId, java.util.Map.of("orderId", orderId));
            }
            return;
        }
        cancelLocally(memberId, orderId, order);
    }

    @Transactional
    protected void cancelLocally(Long memberId, Long orderId, OmsOrder order) {
        List<OmsOrderItem> orderItems = orderItemMapper.selectList(
                new LambdaQueryWrapper<OmsOrderItem>().eq(OmsOrderItem::getOrderId, orderId)
        );
        String inventoryStatus = order.getInventoryReservationStatus() == null
                ? INVENTORY_NOT_RESERVED : order.getInventoryReservationStatus();
        String targetInventoryStatus = inventoryStatus;
        if (INVENTORY_RESERVED.equals(inventoryStatus)) {
            inventoryService.releaseForOrderItems(orderItems);
            targetInventoryStatus = INVENTORY_RELEASED;
        }
        couponService.releaseCouponUsage(memberId, orderId);
        if (orderMapper.transitionOwnedStatusAndInventory(
                orderId,
                memberId,
                OrderStatus.WAIT_PAY.code(),
                OrderStatus.CLOSED.code(),
                inventoryStatus,
                targetInventoryStatus,
                LocalDateTime.now()
        ) != 1) {
            throw new RuntimeException("订单状态已变化，取消失败");
        }
    }

    @Override
    public void confirmReceive(Long memberId, Long orderId) {
        OmsOrder order = requireOwnedOrder(memberId, orderId);
        if (order.getStatus() == null || order.getStatus() != OrderStatus.SHIPPED.code()) {
            throw new RuntimeException("当前订单不可确认收货");
        }
        if (orderMapper.confirmReceived(orderId, memberId, LocalDateTime.now()) != 1) {
            throw new RuntimeException("订单状态已变化，确认收货失败");
        }
    }

    private OmsOrder requireOwnedOrder(Long memberId, Long orderId) {
        OmsOrder order = orderMapper.selectById(orderId);
        if (order == null || order.getDeleteStatus() == 1) {
            throw new RuntimeException("订单不存在");
        }
        if (!memberId.equals(order.getMemberId())) {
            throw new RuntimeException("订单不属于当前用户");
        }
        return order;
    }

    private String generateOrderSn() {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int suffix = ThreadLocalRandom.current().nextInt(100000, 1000000);
        return "AIM" + date + suffix;
    }

    private String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId 不能为空");
        }
        String normalized = requestId.trim();
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("requestId 长度不能超过 64");
        }
        return normalized;
    }
}
