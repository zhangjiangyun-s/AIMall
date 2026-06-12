package com.aimall.server.ai;

import com.aimall.server.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "AI 网关")
@RestController
@RequestMapping("/api/ai")
public class AiGatewayController {

    @Operation(summary = "AI 对话")
    @PostMapping("/chat")
    public ApiResponse<Map<String, Object>> chat(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(Map.of(
                "answer", "这是后端 AI 网关的 mock 回复，后续会转发到 aimall-ai-service。",
                "intent", "PRODUCT_QA",
                "relatedProducts", List.of(),
                "suggestedActions", List.of()
        ));
    }
}
