package com.aimall.server.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "aimall.flyway.production-drill", matches = "true")
class FlywayProductionSnapshotIntegrationTest {

    @Test
    void restoredProductionSnapshotCanReapplyLatestMigrationAndValidate() throws Exception {
        String database = required("AIMALL_FLYWAY_DRILL_DATABASE");
        boolean repairOnly = Boolean.parseBoolean(System.getenv("AIMALL_FLYWAY_REPAIR_ONLY"));
        if ((!repairOnly && !database.startsWith("aimall_restore_stage10_"))
                || (repairOnly && !"aimall".equals(database))) {
            throw new IllegalArgumentException(repairOnly
                    ? "repair-only mode may only target the aimall database"
                    : "drill database must use aimall_restore_stage10_* prefix");
        }
        String username = required("AIMALL_DB_USERNAME");
        String password = required("AIMALL_DB_PASSWORD");
        String url = "jdbc:mysql://127.0.0.1:3306/" + database
                + "?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai"
                + "&useSSL=false&allowPublicKeyRetrieval=true";

        String latestVersion;
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL "
                             + "ORDER BY installed_rank DESC LIMIT 1")) {
            assertTrue(result.next());
            latestVersion = result.getString(1);
        }
        assertEquals("20260722.0900", latestVersion);
        assertSnapshotMatchesMutableMigrationEffects(url, username, password);

        Flyway flyway = Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("20260716")
                .load();
        flyway.repair();
        if (repairOnly) {
            flyway.validate();
            return;
        }
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate(
                    "DELETE FROM flyway_schema_history WHERE version='20260722.0900' AND success=1"));
        }

        MigrateResult migration = flyway.migrate();

        assertEquals(1, migration.migrationsExecuted);
        flyway.validate();
    }

    private void assertSnapshotMatchesMutableMigrationEffects(String url, String username, String password)
            throws Exception {
        String[] returnColumns = {
                "return_carrier", "return_tracking_no", "inspection_result", "inspection_note",
                "sla_deadline", "sla_overdue", "received_time", "active_identity"
        };
        String[] memberColumns = {
                "privacy_consent_version", "privacy_consent_time", "password_changed_at", "cancelled_at"
        };
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            for (String column : returnColumns) {
                assertEquals(1, count(statement, "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema=DATABASE() AND table_name='oms_order_return_apply' "
                        + "AND column_name='" + column + "'"), column);
            }
            for (String column : memberColumns) {
                assertEquals(1, count(statement, "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema=DATABASE() AND table_name='ums_member' "
                        + "AND column_name='" + column + "'"), column);
            }
            for (String table : new String[]{
                    "oms_return_evidence", "oms_return_status_event", "ums_member_login_history", "ums_member_device"
            }) {
                assertEquals(1, count(statement, "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema=DATABASE() AND table_name='" + table + "'"), table);
            }
            assertEquals(1, count(statement, "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                    + "WHERE table_schema=DATABASE() AND table_name='oms_order_return_apply' "
                    + "AND index_name='idx_return_sla'"));
        }
    }

    private int count(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
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
