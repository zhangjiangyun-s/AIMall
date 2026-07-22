package com.aimall.server.health;

import com.aimall.server.security.RequireAdminPermission;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Stage16HealthContractTest {
    @Test
    void capabilityHealthEndpointsRemainSplitAndAdminDiagnosticsAreProtected() {
        Set<String> paths = Arrays.stream(HealthController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(GetMapping.class))
                .filter(mapping -> mapping != null)
                .flatMap(mapping -> Arrays.stream(mapping.value()))
                .collect(Collectors.toSet());
        assertTrue(paths.containsAll(Set.of(
                "/health/liveness", "/health/startup", "/health/readiness/core",
                "/health/readiness/ai", "/health/readiness/payment", "/admin/health"
        )));
        Method admin = Arrays.stream(HealthController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("adminDiagnostics"))
                .findFirst().orElseThrow();
        assertNotNull(admin.getAnnotation(RequireAdminPermission.class));
    }
}
