package com.aimall.server.health;

import com.aimall.server.common.ApiResponse;
import com.aimall.server.config.AlipayProperties;
import com.aimall.server.entity.KnowledgeIndexTask;
import com.aimall.server.entity.OutboxEvent;
import com.aimall.server.mapper.KnowledgeIndexTaskMapper;
import com.aimall.server.mapper.OutboxEventMapper;
import com.aimall.server.security.AdminPermissions;
import com.aimall.server.security.RequireAdminPermission;
import com.aimall.server.tenant.TenantPolicy;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class HealthController {
    private final DataSource dataSource;
    private final RestTemplate restTemplate;
    private final KnowledgeIndexTaskMapper taskMapper;
    private final OutboxEventMapper outboxMapper;
    private final AlipayProperties alipay;
    private final TenantPolicy tenantPolicy;
    private final String aiServiceBaseUrl;
    private final String paymentProvider;
    private final Path storageRoot;
    private final String instanceId = UUID.randomUUID().toString();

    public HealthController(
            DataSource dataSource,
            RestTemplate restTemplate,
            KnowledgeIndexTaskMapper taskMapper,
            OutboxEventMapper outboxMapper,
            AlipayProperties alipay,
            TenantPolicy tenantPolicy,
            @Value("${ai-service.base-url:http://localhost:8000}") String aiServiceBaseUrl,
            @Value("${aimall.payment.provider:SIMULATE}") String paymentProvider,
            @Value("${aimall.knowledge.storage-root:storage/knowledge}") String storageRoot
    ) {
        this.dataSource = dataSource;
        this.restTemplate = restTemplate;
        this.taskMapper = taskMapper;
        this.outboxMapper = outboxMapper;
        this.alipay = alipay;
        this.tenantPolicy = tenantPolicy;
        this.aiServiceBaseUrl = aiServiceBaseUrl.replaceAll("/+$", "");
        this.paymentProvider = paymentProvider == null ? "" : paymentProvider.trim().toUpperCase();
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    @GetMapping({"/health", "/health/liveness"})
    public ApiResponse<Map<String, String>> liveness() {
        return ApiResponse.success(Map.of(
                "service", "aimall-server",
                "status", "UP",
                "instance", instanceId
        ));
    }

    @GetMapping("/health/startup")
    public ResponseEntity<ApiResponse<Map<String, Object>>> startup() {
        Map<String, Object> database = databaseProbe();
        boolean ready = "UP".equals(database.get("status"));
        return readinessResponse(Map.of(
                "service", "aimall-server",
                "status", ready ? "UP" : "DOWN",
                "configuration", Map.of("status", "UP", "tenantMode", tenantPolicy.mode()),
                "migration", Map.of("status", ready ? "UP" : "UNKNOWN"),
                "database", database
        ), ready);
    }

    @GetMapping("/health/readiness/core")
    public ResponseEntity<ApiResponse<Map<String, Object>>> coreReadiness() {
        Map<String, Object> database = databaseProbe();
        Map<String, Object> outbox = outboxProbe();
        boolean ready = "UP".equals(database.get("status")) && "UP".equals(outbox.get("status"));
        return readinessResponse(Map.of(
                "service", "aimall-server",
                "capability", "core",
                "status", ready ? "UP" : "DOWN",
                "database", database,
                "outbox", outbox
        ), ready);
    }

    @GetMapping("/health/readiness/ai")
    public ResponseEntity<ApiResponse<Map<String, Object>>> aiReadiness() {
        Map<String, Object> ai = aiProbe();
        boolean ready = "UP".equals(ai.get("status"));
        return readinessResponse(Map.of(
                "service", "aimall-server",
                "capability", "ai",
                "status", ready ? "UP" : "DOWN",
                "aiService", ai
        ), ready);
    }

    @GetMapping("/health/readiness/payment")
    public ResponseEntity<ApiResponse<Map<String, Object>>> paymentReadiness() {
        Map<String, Object> payment = paymentProbe();
        boolean ready = "UP".equals(payment.get("status"));
        return readinessResponse(Map.of(
                "service", "aimall-server",
                "capability", "payment",
                "status", ready ? "UP" : "DOWN",
                "payment", payment
        ), ready);
    }

    @GetMapping({"/health/integration", "/admin/health"})
    @RequireAdminPermission(AdminPermissions.ADMIN_DIAGNOSTICS)
    public ApiResponse<Map<String, Object>> adminDiagnostics() {
        Map<String, Object> database = databaseProbe();
        Map<String, Object> ai = aiProbe();
        Map<String, Object> payment = paymentProbe();
        Map<String, Object> outbox = outboxProbe();
        Map<String, Object> knowledge = knowledgeProbe();
        Map<String, Object> disk = diskProbe();
        boolean coreUp = "UP".equals(database.get("status")) && "UP".equals(outbox.get("status"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", "aimall-server");
        result.put("status", coreUp ? "UP" : "DEGRADED");
        result.put("tenantMode", tenantPolicy.mode());
        result.put("database", database);
        result.put("aiService", ai);
        result.put("payment", payment);
        result.put("outbox", outbox);
        result.put("knowledgeTasks", knowledge);
        result.put("disk", disk);
        return ApiResponse.success(result);
    }

    private Map<String, Object> databaseProbe() {
        long started = System.nanoTime();
        try (var connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(2);
            return dependency(valid ? "UP" : "DOWN", started, valid ? null : "DATABASE_INVALID");
        } catch (Exception ignored) {
            return dependency("DOWN", started, "DATABASE_UNAVAILABLE");
        }
    }

    private Map<String, Object> aiProbe() {
        long started = System.nanoTime();
        try {
            Map<?, ?> response = restTemplate.getForObject(aiServiceBaseUrl + "/health/readiness/ai", Map.class);
            String reported = response == null ? "DOWN" : String.valueOf(response.get("status")).toUpperCase();
            return dependency("UP".equals(reported) ? "UP" : "DOWN", started,
                    "UP".equals(reported) ? null : "AI_NOT_READY");
        } catch (Exception ignored) {
            return dependency("DOWN", started, "AI_UNAVAILABLE");
        }
    }

    private Map<String, Object> paymentProbe() {
        long started = System.nanoTime();
        boolean provider = "ALIPAY_SANDBOX".equals(paymentProvider);
        boolean configured = provider && alipay.isEnabled()
                && present(alipay.getAppId()) && present(alipay.getSellerId())
                && readable(alipay.getPrivateKeyFile()) && readable(alipay.getPublicKeyFile())
                && present(alipay.getNotifyBaseUrl());
        return dependency(configured ? "UP" : "DOWN", started,
                configured ? null : "PAYMENT_PROVIDER_NOT_READY");
    }

    private Map<String, Object> outboxProbe() {
        long started = System.nanoTime();
        try {
            long dead = outboxMapper.selectCount(
                    new LambdaQueryWrapper<OutboxEvent>().eq(OutboxEvent::getStatus, "DEAD_LETTER"));
            Map<String, Object> result = dependency(dead == 0 ? "UP" : "DOWN", started,
                    dead == 0 ? null : "OUTBOX_DEAD_LETTER");
            result.put("deadLetterCount", dead);
            return result;
        } catch (Exception ignored) {
            return dependency("DOWN", started, "OUTBOX_UNAVAILABLE");
        }
    }

    private Map<String, Object> knowledgeProbe() {
        long started = System.nanoTime();
        try {
            long active = taskMapper.selectCount(new LambdaQueryWrapper<KnowledgeIndexTask>()
                    .in(KnowledgeIndexTask::getStatus, "PENDING", "RUNNING", "RETRY_WAIT"));
            long dead = taskMapper.selectCount(new LambdaQueryWrapper<KnowledgeIndexTask>()
                    .eq(KnowledgeIndexTask::getStatus, "DEAD_LETTER"));
            Map<String, Object> result = dependency(dead == 0 ? "UP" : "DEGRADED", started,
                    dead == 0 ? null : "KNOWLEDGE_TASK_DEAD_LETTER");
            result.put("activeCount", active);
            result.put("deadLetterCount", dead);
            return result;
        } catch (Exception ignored) {
            return dependency("DOWN", started, "KNOWLEDGE_TASK_UNAVAILABLE");
        }
    }

    private Map<String, Object> diskProbe() {
        long started = System.nanoTime();
        try {
            Files.createDirectories(storageRoot);
            var store = Files.getFileStore(storageRoot);
            boolean ready = store.getUsableSpace() >= 512L * 1024 * 1024
                    && store.getUsableSpace() * 100L / Math.max(1L, store.getTotalSpace()) >= 5L;
            return dependency(ready ? "UP" : "DOWN", started, ready ? null : "DISK_CAPACITY_LOW");
        } catch (Exception ignored) {
            return dependency("DOWN", started, "DISK_UNAVAILABLE");
        }
    }

    private Map<String, Object> dependency(String status, long started, String errorCode) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("status", status);
        value.put("latencyMs", Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
        value.put("lastSuccessAt", "UP".equals(status) ? Instant.now().toString() : "");
        value.put("errorCode", errorCode == null ? "" : errorCode);
        return value;
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> readinessResponse(
            Map<String, Object> value, boolean ready
    ) {
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.success(value));
    }

    private boolean readable(String value) {
        try {
            return present(value) && Files.isRegularFile(Path.of(value)) && Files.isReadable(Path.of(value));
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
