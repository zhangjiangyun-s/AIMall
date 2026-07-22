package com.aimall.server.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.aimall.server.observability.OperationalMetricsService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "aimall.mysql.integration", matches = "true")
class FlywayMigrationIntegrationTest {
    private static final List<String> databases = new ArrayList<>();
    private static String adminUrl;
    private static String username;
    private static String password;

    @BeforeAll
    static void configureDatabase() throws Exception {
        Map<String, String> environment = loadEnvironment();
        username = environment.getOrDefault("AIMALL_DB_USERNAME", "root");
        password = environment.getOrDefault("AIMALL_DB_PASSWORD", "123456");
        adminUrl = "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&characterEncoding=utf-8"
                + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
    }

    @AfterAll
    static void dropDatabases() throws Exception {
        try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
             Statement statement = connection.createStatement()) {
            for (String database : databases) {
                statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            }
        }
    }

    @Test
    void emptyDatabaseMigratesToLatestUsingFlywayOnly() throws Exception {
        String database = createDatabase("empty");

        Flyway flyway = migrate(database);

        assertNotNull(flyway.info().current());
        assertEquals("20260721.1900", flyway.info().current().getVersion().toString());
        assertTrue(tableExists(database, "oms_order"));
        assertTrue(tableExists(database, "flyway_schema_history"));
        assertTrue(columnExists(database, "outbox_event", "occurred_at_utc"));
        assertTrue(columnExists(database, "outbox_event", "producer_version"));
        assertTrue(columnExists(database, "inventory_ledger", "available_delta"));
        assertTrue(columnExists(database, "pms_product", "price_v2"));
        assertTrue(columnExists(database, "pms_sku_stock", "price_v2"));
        assertTrue(columnExists(database, "outbox_event", "payload_schema_version"));
        assertTrue(columnExists(database, "outbox_event", "tenant_id"));
        assertTrue(columnExists(database, "payment_callback_event", "trace_id"));
        assertTrue(columnExists(database, "knowledge_index_task", "trace_id"));
        assertTrue(columnExists(database, "ums_member_coupon", "usage_state"));
        assertTrue(columnExists(database, "knowledge_doc_version", "publication_version"));
        assertTrue(columnExists(database, "knowledge_chunk", "retrieval_epoch"));
        assertTrue(columnExists(database, "embedding_cache", "retrieval_epoch"));
        assertTrue(tableExists(database, "knowledge_retrieval_epoch"));
        assertTrue(indexExists(database, "embedding_cache", "uk_embedding_cache_hash_model_epoch"));
        DriverManagerDataSource dataSource = new DriverManagerDataSource(databaseUrl(database), username, password);
        OperationalMetricsService metrics = new OperationalMetricsService(
                new JdbcTemplate(dataSource), new SimpleMeterRegistry()
        );
        metrics.refresh();
        assertEquals(0L, metrics.snapshot().get("aimall_observability_query_failures"));
        assertTrue(metrics.snapshot().get("aimall_mysql_row_lock_time_max_ms") >= 0L);
    }

    @Test
    void preFlywaySchemaCanBeBaselinedAndUpgraded() throws Exception {
        String database = createDatabase("upgrade");
        applyLegacySchema(database);

        Flyway flyway = migrate(database);

        assertEquals("20260721.1900", flyway.info().current().getVersion().toString());
        assertTrue(columnExists(database, "oms_payment_record", "transaction_no"));
        assertTrue(indexExists(database, "oms_payment_record", "uk_oms_payment_transaction_no"));
    }

    @Test
    void partiallyAppliedDdlCanResumeWithoutDuplicateColumnFailure() throws Exception {
        String database = createDatabase("partial");
        applyLegacySchema(database);
        execute(database, "ALTER TABLE knowledge_index_task DROP COLUMN attempt_no");
        execute(database, "ALTER TABLE knowledge_chunk DROP COLUMN retrieval_epoch");
        assertTrue(columnExists(database, "knowledge_index_task", "execution_token"));

        Flyway flyway = migrate(database);

        assertEquals("20260721.1900", flyway.info().current().getVersion().toString());
        assertTrue(columnExists(database, "knowledge_index_task", "execution_token"));
        assertTrue(columnExists(database, "knowledge_index_task", "attempt_no"));
        assertTrue(columnExists(database, "knowledge_chunk", "retrieval_epoch"));
    }

    private static Flyway migrate(String database) {
        Flyway flyway = Flyway.configure()
                .dataSource(databaseUrl(database), username, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("20260716"))
                .validateOnMigrate(true)
                .load();
        flyway.migrate();
        return flyway;
    }

    private static String createDatabase(String suffix) throws Exception {
        String database = "aimall_flyway_" + suffix + "_"
                + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + database
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        databases.add(database);
        return database;
    }

    private static void applyLegacySchema(String database) throws Exception {
        String schema = Files.readString(
                        Path.of("src", "main", "resources", "schema.sql"), StandardCharsets.UTF_8)
                .replace(
                        "CREATE DATABASE IF NOT EXISTS aimall DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;",
                        ""
                )
                .replace("USE aimall;", "");
        try (Connection connection = DriverManager.getConnection(databaseUrl(database), username, password)) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(
                            new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8)),
                            StandardCharsets.UTF_8
                    )
            );
        }
    }

    private static void execute(String database, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(databaseUrl(database), username, password);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static boolean tableExists(String database, String table) throws Exception {
        return metadataCount(database, "tables", "table_name", table) == 1;
    }

    private static boolean columnExists(String database, String table, String column) throws Exception {
        return metadataCount(database, "columns", "table_name", table, "column_name", column) == 1;
    }

    private static boolean indexExists(String database, String table, String index) throws Exception {
        return metadataCount(database, "statistics", "table_name", table, "index_name", index) >= 1;
    }

    private static int metadataCount(String database, String metadataTable, String... predicates) throws Exception {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM information_schema.")
                .append(metadataTable).append(" WHERE table_schema = '").append(database).append("'");
        for (int index = 0; index < predicates.length; index += 2) {
            sql.append(" AND ").append(predicates[index]).append(" = '").append(predicates[index + 1]).append("'");
        }
        try (Connection connection = DriverManager.getConnection(databaseUrl(database), username, password);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql.toString())) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static String databaseUrl(String database) {
        return adminUrl.replace("3306/?", "3306/" + database + "?");
    }

    private static Map<String, String> loadEnvironment() throws Exception {
        Map<String, String> values = new HashMap<>(System.getenv());
        Path envFile = Path.of("..", ".env").normalize();
        if (!Files.exists(envFile)) {
            return values;
        }
        for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            String key = trimmed.substring(0, separator).trim();
            String value = trimmed.substring(separator + 1).trim();
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            values.putIfAbsent(key, value);
        }
        return values;
    }
}
