package com.aimall.server.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionConfigurationValidatorTest {
    @Test
    void localEnvironmentMayUseLocalDevelopmentDefaults() {
        assertDoesNotThrow(() -> new ProductionConfigurationValidator(
                "local", "root", "123456", "", "", true, true, "http://localhost:8000", "http://localhost:5173", ""
        ).validate());
    }

    @Test
    void productionRejectsWeakDatabaseCredentials() {
        assertThrows(IllegalStateException.class, () -> new ProductionConfigurationValidator(
                "prod", "aimall_app", "123456", "a".repeat(32), "A".repeat(16), false, false,
                "http://aimall-ai-service:8000", "https://shop.example.com", "sandbox"
        ).validate());
    }

    @Test
    void productionRejectsRootAndSimulation() {
        assertThrows(IllegalStateException.class, () -> new ProductionConfigurationValidator(
                "production", "root", "strong-password", "a".repeat(32), "A".repeat(16), true, false,
                "http://aimall-ai-service:8000", "https://shop.example.com", "sandbox"
        ).validate());
    }

    @Test
    void productionAcceptsExplicitSafeConfiguration() {
        assertDoesNotThrow(() -> new ProductionConfigurationValidator(
                "prod", "aimall_app", "strong-password", "a".repeat(32), "A".repeat(16), false, false,
                "http://aimall-ai-service:8000", "https://shop.example.com", "sandbox"
        ).validate());
    }

    @Test
    void productionRejectsMailhogAndLocalEmailPepper() {
        assertThrows(IllegalStateException.class, () -> new ProductionConfigurationValidator(
                "prod", "aimall_app", "strong-password", "a".repeat(32), "A".repeat(16), false, false,
                "http://aimall-ai-service:8000", "https://shop.example.com", "sandbox",
                true, "mailhog", "aimall-local-mailhog-pepper-not-for-production"
        ).validate());
    }
}
