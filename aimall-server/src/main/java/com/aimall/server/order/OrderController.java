package com.aimall.server.order;

import com.aimall.server.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "订单")
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Operation(summary = "订单列表")
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        List<Map<String, Object>> orders = List.of(
                Map.of(
                        "orderId", 9001,
                        "orderNo", "OD202606110001",
                        "status", "WAIT_SHIP",
                        "statusText", "待发货",
                        "totalAmount", 399,
                        "items", List.of(
                                Map.of(
                                        "productId", 1003,
                                        "productName", "无线蓝牙耳机 C3",
                                        "quantity", 1,
                                        "price", 399
                                )
                        )
                )
        );
        return ApiResponse.success(orders);
    }

    @Operation(summary = "订单详情")
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Integer id) {
        return ApiResponse.success(getOrderDetail(id));
    }

    private Map<String, Object> getOrderDetail(Integer id) {
        if (id == 9001) {
            return Map.of(
                    "orderId", 9001,
                    "orderNo", "OD202606110001",
                    "status", "WAIT_SHIP",
                    "statusText", "待发货",
                    "totalAmount", 399,
                    "items", List.of(
                            Map.of(
                                    "productId", 1003,
                                    "productName", "无线蓝牙耳机 C3",
                                    "quantity", 1,
                                    "price", 399
                            )
                    )
            );
        }
        return Map.of(
                "orderId", 0,
                "orderNo", "UNKNOWN_ORDER_MOCK",
                "status", "UNKNOWN",
                "statusText", "未知订单",
                "totalAmount", 0,
                "items", List.of()
        );
    }
}
