package com.aimall.server.admin;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeLegacyPathRemovalTest {
    @Test
    void legacyKnowledgeWriteRoutesAreNotRegistered() {
        for (Method method : AdminController.class.getDeclaredMethods()) {
            PostMapping post = method.getAnnotation(PostMapping.class);
            PutMapping put = method.getAnnotation(PutMapping.class);
            if (post != null) {
                assertFalse(Arrays.asList(post.value()).contains("/knowledge-docs"));
            }
            if (put != null) {
                assertFalse(Arrays.asList(put.value()).contains("/knowledge-docs/{id}"));
            }
        }
    }

    @Test
    void legacyUnversionedChunkBuilderCannotReturn() {
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("com.aimall.server.service.impl.KnowledgeChunkBuildServiceImpl")
        );
    }

    @Test
    void freshInitializationCannotSeedUnversionedKnowledge() throws Exception {
        try (var dataStream = getClass().getClassLoader().getResourceAsStream("data.sql");
             var configStream = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertNotNull(dataStream);
            assertNotNull(configStream);
            String dataSql = new String(dataStream.readAllBytes(), StandardCharsets.UTF_8);
            String application = new String(configStream.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(dataSql.contains("INSERT INTO `knowledge_doc`"));
            assertTrue(application.contains("classpath:db/migration"));
            assertFalse(application.contains("spring.sql.init"));
            assertFalse(application.contains("schema-locations"));
            assertNotNull(getClass().getClassLoader().getResource(
                    "db/migration/V20260716_0000__baseline_schema.sql"
            ));
            assertNotNull(getClass().getClassLoader().getResource(
                    "db/migration/V20260717_1100__legacy_business_schema_reconciliation.sql"
            ));
        }
    }

    @Test
    void dockerAndApplicationCannotExecuteLegacySqlInitialization() throws Exception {
        String compose = Files.readString(Path.of("..", "docker-compose.yml"), StandardCharsets.UTF_8);
        assertFalse(compose.contains("docker-entrypoint-initdb.d"));
        assertFalse(compose.contains("src/main/resources/migrations"));
        assertFalse(compose.contains("src/main/resources/schema.sql"));
        assertFalse(compose.contains("src/main/resources/data.sql"));
    }

    @Test
    void flywayReconciliationTracksEveryFormerManualMigration() throws Exception {
        try (var migrationStream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V20260717_1100__legacy_business_schema_reconciliation.sql"
        )) {
            assertNotNull(migrationStream);
            String migration = new String(migrationStream.readAllBytes(), StandardCharsets.UTF_8);
            for (String formerMigration : new String[]{
                    "20260716_cart_uniqueness.sql", "20260716_transaction_safety.sql",
                    "20260717_admin_operation_audit.sql", "20260717_admin_security.sql",
                    "20260717_coupon_model.sql", "20260717_internal_nonce.sql",
                    "20260717_knowledge_version_guard.sql", "20260717_logistics.sql",
                    "20260717_order_expiration.sql", "20260717_order_inventory_state.sql",
                    "20260717_product_catalog.sql", "20260717_refund_manual_recovery.sql",
                    "20260717_refund_task_state.sql", "20260717_remove_legacy_home_recommend.sql",
                    "20260717_return_items.sql", "20260717_return_uniqueness.sql"
            }) {
                assertTrue(migration.contains(formerMigration), formerMigration + " must be reconciled by Flyway");
            }
        }
    }
}
