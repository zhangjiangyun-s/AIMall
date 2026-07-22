package com.aimall.server.order;

import cn.dev33.satoken.stp.StpUtil;
import com.aimall.server.common.ApiResponse;
import com.aimall.server.common.RequestUtils;
import com.aimall.server.order.dto.OrderCreateRequest;
import com.aimall.server.order.dto.OrderPreviewRequest;
import com.aimall.server.entity.OmsOrder;
import com.aimall.server.entity.OmsOrderItem;
import com.aimall.server.mapper.OmsOrderItemMapper;
import com.aimall.server.service.CouponService;
import com.aimall.server.service.OrderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final CouponService couponService;
    private final OmsOrderItemMapper orderItemMapper;

    public OrderController(OrderService orderService, CouponService couponService, OmsOrderItemMapper orderItemMapper) {
        this.orderService = orderService;
        this.couponService = couponService;
        this.orderItemMapper = orderItemMapper;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        long memberId = StpUtil.getLoginIdAsLong();
        List<OmsOrder> orders = orderService.listByMember(memberId);
        if (orders.isEmpty()) {
            return ApiResponse.success(List.of());
        }
        List<Long> orderIds = orders.stream().map(OmsOrder::getId).toList();
        Map<Long, List<OmsOrderItem>> itemsByOrderId = orderItemMapper.selectList(
                new LambdaQueryWrapper<OmsOrderItem>().in(OmsOrderItem::getOrderId, orderIds)
        ).stream().collect(Collectors.groupingBy(OmsOrderItem::getOrderId));
        return ApiResponse.success(
                orders.stream()
                        .map(order -> toOrderSummary(order, itemsByOrderId.getOrDefault(order.getId(), Collections.emptyList())))
                        .collect(Collectors.toList())
        );
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        OmsOrder order = orderService.getById(id);
        long memberId = StpUtil.getLoginIdAsLong();
        if (order == null || order.getDeleteStatus() == 1 || order.getMemberId() == null || order.getMemberId() != memberId) {
            throw new RuntimeException("订单不存在");
        }
        return ApiResponse.success(toOrderSummary(order));
    }

    @PostMapping("/preview")
    public ApiResponse<Map<String, Object>> preview(@Valid @RequestBody OrderPreviewRequest params) {
        long memberId = StpUtil.getLoginIdAsLong();
        return ApiResponse.success(couponService.previewOrder(
                memberId,
                params.cartItemIds(),
                params.memberCouponId()
        ));
    }

    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody OrderCreateRequest params) {
        long memberId = StpUtil.getLoginIdAsLong();
        OmsOrder order = orderService.create(
                memberId, params.requestId(), params.addressId(), params.memberCouponId(), params.cartItemIds()
        );
        return ApiResponse.success(Map.of("orderId", order.getId(), "orderSn", order.getOrderSn()));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable Long id) {
        orderService.cancel(StpUtil.getLoginIdAsLong(), id);
        OmsOrder current = orderService.getById(id);
        boolean closed = current != null && current.getStatus() != null && current.getStatus() == 4;
        return ApiResponse.success(Map.of(
                "message", closed ? "订单已取消" : "取消请求处理中",
                "status", closed ? "CLOSED" : "CANCELLING"
        ));
    }

    @PostMapping("/{id}/confirm-receive")
    public ApiResponse<Map<String, Object>> confirmReceive(@PathVariable Long id) {
        orderService.confirmReceive(StpUtil.getLoginIdAsLong(), id);
        return ApiResponse.success(Map.of("message", "已确认收货"));
    }

    private Map<String, Object> toOrderSummary(OmsOrder order) {
        List<OmsOrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OmsOrderItem>().eq(OmsOrderItem::getOrderId, order.getId())
        );
        return toOrderSummary(order, items);
    }

    private Map<String, Object> toOrderSummary(OmsOrder order, List<OmsOrderItem> items) {
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", order.getId());
        data.put("orderNo", order.getOrderSn());
        data.put("status", statusCode(order.getStatus()));
        data.put("statusText", statusText(order.getStatus()));
        data.put("totalAmount", order.getTotalAmount());
        data.put("payAmount", order.getPayAmount());
        data.put("freightAmount", order.getFreightAmount());
        data.put("couponAmount", order.getCouponAmount());
        data.put("discountAmount", order.getDiscountAmount());
        data.put("receiverName", order.getReceiverName());
        data.put("receiverPhone", order.getReceiverPhone());
        data.put("receiverProvince", order.getReceiverProvince());
        data.put("receiverCity", order.getReceiverCity());
        data.put("receiverRegion", order.getReceiverRegion());
        data.put("receiverDetailAddress", order.getReceiverDetailAddress());
        data.put("deliveryCompany", order.getDeliveryCompany() == null ? "" : order.getDeliveryCompany());
        data.put("deliverySn", order.getDeliverySn() == null ? "" : order.getDeliverySn());
        data.put("createTime", order.getCreateTime() == null ? "" : order.getCreateTime().toString().replace("T", " "));
        data.put("paymentTime", order.getPaymentTime() == null ? "" : order.getPaymentTime().toString().replace("T", " "));
        data.put("deliveryTime", order.getDeliveryTime() == null ? "" : order.getDeliveryTime().toString().replace("T", " "));
        data.put("receiveTime", order.getReceiveTime() == null ? "" : order.getReceiveTime().toString().replace("T", " "));
        data.put("items", items.stream().map(item -> {
            Map<String, Object> row = new HashMap<>();
            row.put("productId", item.getProductId());
            row.put("productName", item.getProductName());
            row.put("productBrand", item.getProductBrand() == null ? "" : item.getProductBrand());
            row.put("quantity", item.getProductQuantity());
            row.put("price", item.getProductPrice());
            row.put("skuCode", item.getProductSkuCode() == null ? "" : item.getProductSkuCode());
            row.put("productAttr", item.getProductAttr() == null ? "" : item.getProductAttr());
            row.put("realAmount", item.getRealAmount());
            return row;
        }).collect(Collectors.toList()));
        return data;
    }

    private String statusCode(Integer status) {
        if (status == null) {
            return "UNKNOWN";
        }
        return switch (status) {
            case 0 -> "WAIT_PAY";
            case 1 -> "WAIT_SHIP";
            case 2 -> "SHIPPED";
            case 3 -> "COMPLETED";
            case 4 -> "CLOSED";
            case 5 -> "INVALID";
            default -> "UNKNOWN";
        };
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 0 -> "待支付";
            case 1 -> "待发货";
            case 2 -> "已发货";
            case 3 -> "已完成";
            case 4 -> "已关闭";
            case 5 -> "无效订单";
            default -> "未知";
        };
    }
}
