package com.aimall.server.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "aimall.flyway.recovery-drill", matches = "true")
class FlywayDisasterRecoveryIntegrationTest {

    @Test
    void restoredSnapshotMigratesToLatestAndRetainsRecoveryFacts() throws Exception {
        String database = required("AIMALL_FLYWAY_DRILL_DATABASE");
        if (!database.startsWith("aimall_restore_stage20_")) {
            throw new IllegalArgumentException("recovery database must use aimall_restore_stage20_* prefix");
        }
        String username = required("AIMALL_DB_USERNAME");
        String password = required("AIMALL_DB_PASSWORD");
        String url = "jdbc:mysql://127.0.0.1:3306/" + database
                + "?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai"
                + "&useSSL=false&allowPublicKeyRetrieval=true";
        long ordersBefore = count(url, username, password, "oms_order");
        long paymentsBefore = count(url, username, password, "oms_payment_record");
        long refundsBefore = count(url, username, password, "oms_refund_record");

        Flyway flyway = Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("20260716"))
                .validateOnMigrate(true)
                .load();
        flyway.migrate();
        flyway.validate();

        assertEquals("20260721.1900", flyway.info().current().getVersion().toString());
        assertTrue(count(url, username, password, "oms_order") >= ordersBefore);
        assertTrue(count(url, username, password, "oms_payment_record") >= paymentsBefore);
        assertTrue(count(url, username, password, "oms_refund_record") >= refundsBefore);
        for (String table : new String[]{
                "inventory_ledger", "outbox_event", "admin_operation_audit",
                "knowledge_doc_audit_log", "knowledge_doc_version", "knowledge_retrieval_epoch"
        }) {
            assertEquals(1, metadataCount(url, username, password, "tables", "table_name", table), table);
        }
        assertEquals(1, metadataCount(
                url, username, password, "columns", "table_name", "knowledge_chunk",
                "column_name", "retrieval_epoch"
        ));
    }

    private long count(String url, String username, String password, String table) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private int metadataCount(String url, String username, String password,
                              String metadataTable, String... predicates) throws Exception {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM information_schema.")
                .append(metadataTable).append(" WHERE table_schema=DATABASE()");
        for (int index = 0; index < predicates.length; index += 2) {
            sql.append(" AND ").append(predicates[index]).append("='")
                    .append(predicates[index + 1]).append("'");
        }
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql.toString())) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
