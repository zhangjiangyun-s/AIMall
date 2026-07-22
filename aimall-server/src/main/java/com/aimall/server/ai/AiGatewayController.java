package com.aimall.server.ai;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.aimall.server.ai.dto.AiChatRequest;
import com.aimall.server.ai.dto.AiFeedbackRequest;
import com.aimall.server.ai.dto.AiSessionRequest;
import com.aimall.server.ai.dto.AiPendingActionRequest;
import jakarta.validation.Valid;
import com.aimall.server.tenant.TenantPolicy;

@RestController
@RequestMapping("/api/ai")
public class AiGatewayController {

    private static final Logger log = LoggerFactory.getLogger(AiGatewayController.class);

    private final String aiServiceBaseUrl;
    private final String fastApiUrl;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TenantPolicy tenantPolicy;

    public AiGatewayController(
            @Value("${ai-service.base-url:http://localhost:8000}") String aiBaseUrl,
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            TenantPolicy tenantPolicy
    ) {
        this.aiServiceBaseUrl = aiBaseUrl;
        this.fastApiUrl = aiBaseUrl + "/ai/chat";
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.tenantPolicy = tenantPolicy;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chat(
            @Valid @RequestBody AiChatRequest request,
            @RequestHeader(value = "token", required = false) String token
    ) {
        tenantPolicy.resolve(request.tenantId());
        String currentTraceId = MDC.get("traceId");
        final String traceId = currentTraceId == null || currentTraceId.isBlank()
                ? UUID.randomUUID().toString() : currentTraceId;

        StreamingResponseBody stream = outputStream -> {
            try {
                Map<String, Object> payload = request.toPayload(
                        StpUtil.getLoginIdAsLong(), traceId, buildAuthContext(token)
                );

                restTemplate.execute(
                        fastApiUrl,
                        HttpMethod.POST,
                        clientRequest -> {
                            clientRequest.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                            objectMapper.writeValue(clientRequest.getBody(), payload);
                        },
                        clientResponse -> {
                            try (InputStream inputStream = clientResponse.getBody()) {
                                if (inputStream == null) {
                                    throw new IllegalStateException("empty ai stream");
                                }
                                copyStream(inputStream, outputStream);
                            }
                            return null;
                        }
                );
            } catch (Exception ex) {
                log.warn("AI chat stream fallback, traceId={}, url={}", traceId, fastApiUrl, ex);
                writeFallback(outputStream, traceId);
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noCache())
                .header("Connection", "keep-alive")
                .header("X-Accel-Buffering", "no")
                .header("X-Trace-Id", traceId)
                .body(stream);
    }

    @PostMapping("/feedback")
    public ResponseEntity<Map<String, Object>> feedback(
            @Valid @RequestBody AiFeedbackRequest request,
            @RequestHeader(value = "token", required = false) String token
    ) {
        try {
            Map<String, Object> payload = request.toPayload(
                    StpUtil.getLoginIdAsLong(), buildAuthContext(token)
            );
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(aiServiceBaseUrl + "/ai/feedback", payload, Map.class);
            return ResponseEntity.ok(response == null ? Map.of("code", 0, "data", Map.of("success", true)) : response);
        } catch (Exception ex) {
            log.warn("AI feedback write failed, traceId={}", request.traceId(), ex);
            return aiServiceUnavailable("AI 反馈服务暂时不可用");
        }
    }

    @PostMapping("/session/clear")
    public ResponseEntity<Map<String, Object>> clearSession(
            @Valid @RequestBody AiSessionRequest request,
            @RequestHeader(value = "token", required = false) String token
    ) {
        tenantPolicy.resolve(request.tenantId());
        try {
            Map<String, Object> payload = request.toPayload(
                    StpUtil.getLoginIdAsLong(), buildAuthContext(token)
            );
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    aiServiceBaseUrl + "/ai/session/clear",
                    payload,
                    Map.class
            );
            return ResponseEntity.ok(response == null ? Map.of("code", 0, "data", Map.of("cleared", false)) : response);
        } catch (Exception ex) {
            log.warn("AI session clear failed, sessionId={}", request.sessionId(), ex);
            return aiServiceUnavailable("AI 会话服务暂时不可用");
        }
    }

    @PostMapping("/actions/{actionId}/confirm")
    public ResponseEntity<Map<String, Object>> confirmAction(
            @PathVariable String actionId,
            @Valid @RequestBody AiPendingActionRequest request,
            @RequestHeader(value = "token", required = false) String token
    ) {
        tenantPolicy.resolve(request.tenantId());
        return proxyAuthenticatedPost("/ai/actions/" + actionId + "/confirm", request, token);
    }

    @PostMapping("/actions/{actionId}/reject")
    public ResponseEntity<Map<String, Object>> rejectAction(
            @PathVariable String actionId,
            @Valid @RequestBody AiPendingActionRequest request,
            @RequestHeader(value = "token", required = false) String token
    ) {
        tenantPolicy.resolve(request.tenantId());
        return proxyAuthenticatedPost("/ai/actions/" + actionId + "/reject", request, token);
    }

    @PostMapping("/actions/{actionId}/status")
    public ResponseEntity<Map<String, Object>> actionStatus(
            @PathVariable String actionId,
            @Valid @RequestBody AiPendingActionRequest request,
            @RequestHeader(value = "token", required = false) String token
    ) {
        tenantPolicy.resolve(request.tenantId());
        return proxyAuthenticatedPost("/ai/actions/" + actionId + "/status", request, token);
    }

    @GetMapping("/vector/health")
    public ResponseEntity<Map<String, Object>> vectorHealth() {
        return proxyGet("/ai/vector/health");
    }

    @PostMapping("/vector/sync")
    public ResponseEntity<Map<String, Object>> vectorSync(
            @RequestParam(value = "syncStatus", required = false, defaultValue = "PENDING") String syncStatus,
            @RequestParam(value = "limit", required = false, defaultValue = "100") Integer limit
    ) {
        return proxyPost("/ai/vector/sync?syncStatus=" + syncStatus + "&limit=" + limit, Map.of());
    }

    @PostMapping("/vector/consistency-check")
    public ResponseEntity<Map<String, Object>> vectorConsistencyCheck(
            @RequestParam(value = "limit", required = false, defaultValue = "500") Integer limit
    ) {
        return proxyPost("/ai/vector/consistency-check?limit=" + limit, Map.of());
    }

    private ResponseEntity<Map<String, Object>> proxyGet(String path) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(aiServiceBaseUrl + path, Map.class);
            return ResponseEntity.ok(response == null ? Map.of("code", 0, "data", Map.of()) : response);
        } catch (Exception ex) {
            log.warn("AI proxy GET failed, path={}", path, ex);
            return aiServiceUnavailable("AI 服务暂时不可用");
        }
    }

    private ResponseEntity<Map<String, Object>> proxyPost(String path, Map<String, Object> request) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(aiServiceBaseUrl + path, request, Map.class);
            return ResponseEntity.ok(response == null ? Map.of("code", 0, "data", Map.of()) : response);
        } catch (Exception ex) {
            log.warn("AI proxy POST failed, path={}", path, ex);
            return aiServiceUnavailable("AI 服务暂时不可用");
        }
    }

    private ResponseEntity<Map<String, Object>> aiServiceUnavailable(String message) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                "code", 1,
                "message", message,
                "data", Map.of("success", false)
        ));
    }

    private ResponseEntity<Map<String, Object>> proxyAuthenticatedPost(
            String path,
            AiPendingActionRequest request,
            String token
    ) {
        Map<String, Object> payload = request.toPayload(
                StpUtil.getLoginIdAsLong(), buildAuthContext(token)
        );
        return proxyPost(path, payload);
    }

    private Map<String, Object> buildAuthContext(String token) {
        Map<String, Object> authContext = new HashMap<>();
        authContext.put("channel", "aimall-web");
        if (token != null && !token.isBlank()) {
            authContext.put("token", token);
        }
        return authContext;
    }

    private void copyStream(InputStream inputStream, OutputStream outputStream) throws java.io.IOException {
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, length);
            outputStream.flush();
        }
    }

    private void writeFallback(OutputStream outputStream, String traceId) throws java.io.IOException {
        writeSse(outputStream, Map.of(
                "type", "delta",
                "content", "我是 AIMall AI 购物助手。AI 服务暂时不可用，请稍后再试。",
                "traceId", traceId
        ));
        writeSse(outputStream, Map.of(
                "type", "done",
                "intent", "GENERAL_QA",
                "relatedProducts", List.of(),
                "suggestedActions", List.of(
                        Map.of("type", "OPEN_PRODUCTS", "label", "浏览商品"),
                        Map.of("type", "OPEN_ORDERS", "label", "查看订单")
                ),
                "toolCalls", List.of(),
                "traceId", traceId,
                "degraded", true
        ));
    }

    private void writeSse(OutputStream outputStream, Map<String, Object> payload) throws java.io.IOException {
        String event = "data: " + objectMapper.writeValueAsString(payload) + "\n\n";
        outputStream.write(event.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }
}
