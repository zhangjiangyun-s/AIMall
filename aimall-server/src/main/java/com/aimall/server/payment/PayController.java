package com.aimall.server.payment;

import cn.dev33.satoken.stp.StpUtil;
import com.aimall.server.common.ApiResponse;
import com.aimall.server.common.RequestUtils;
import com.aimall.server.payment.dto.PaymentOrderRequest;
import com.aimall.server.service.PayService;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.Map;
import org.springframework.util.MultiValueMap;
import org.springframework.web.servlet.view.RedirectView;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/pay")
public class PayController {

    private final PayService payService;
    private final boolean simulationEnabled;

    public PayController(PayService payService, @Value("${aimall.payment.simulation-enabled:false}") boolean simulationEnabled) {
        this.payService = payService;
        this.simulationEnabled = simulationEnabled;
    }

    @PostMapping("/simulate")
    public ApiResponse<Map<String, Object>> simulate(@Valid @RequestBody PaymentOrderRequest params) {
        if (!simulationEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        payService.simulatePay(
                params.orderId(),
                StpUtil.getLoginIdAsLong()
        );
        return ApiResponse.success(Map.of("message", "支付成功"));
    }

    @PostMapping("/alipay/create")
    public ApiResponse<Map<String, Object>> createAlipay(@Valid @RequestBody PaymentOrderRequest params) {
        return ApiResponse.success(payService.createAlipayPayment(
                params.orderId(), StpUtil.getLoginIdAsLong()));
    }

    @PostMapping("/alipay/query/{orderId}")
    public ApiResponse<Map<String, Object>> queryAlipay(@PathVariable Long orderId) {
        return ApiResponse.success(payService.queryAlipayPayment(orderId, StpUtil.getLoginIdAsLong()));
    }

    @PostMapping("/alipay/notify")
    public String alipayNotify(@RequestParam MultiValueMap<String, String> request) {
        return payService.handleAlipayNotify(request.toSingleValueMap());
    }

    @GetMapping("/alipay/return")
    public RedirectView alipayReturn(@RequestParam MultiValueMap<String, String> request) {
        return new RedirectView(payService.handleAlipayReturn(request.toSingleValueMap()));
    }

    @GetMapping("/status/{orderId}")
    public ApiResponse<Map<String, Object>> status(@PathVariable Long orderId) {
        return ApiResponse.success(payService.getPayStatus(orderId, StpUtil.getLoginIdAsLong()));
    }
}
