package com.aimall.server.health;

import com.aimall.server.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "健康检查")
@RestController
@RequestMapping("/api")
public class HealthController {

    @Operation(summary = "健康检查")
    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.success(Map.of(
                "service", "aimall-server",
                "status", "UP"
        ));
    }

    @Operation(summary = "联调自检")
    @GetMapping("/health/integration")
    public ApiResponse<Map<String, Object>> integrationHealth() {
        Map<String, Object> modules = Map.of(
                "product", true,
                "order", true,
                "admin", true,
                "aiGateway", true,
                "database", false
        );
        Map<String, Object> ports = Map.of(
                "server", 8080,
                "web", 5173,
                "admin", 5174,
                "aiService", 8000
        );
        Map<String, Object> data = Map.of(
                "service", "aimall-server",
                "status", "UP",
                "version", "1D-mock",
                "modules", modules,
                "ports", ports
        );
        return ApiResponse.success(data);
    }
}
