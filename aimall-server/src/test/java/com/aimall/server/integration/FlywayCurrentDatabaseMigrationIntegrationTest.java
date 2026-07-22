package com.aimall.server.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfSystemProperty(named = "aimall.flyway.current-migrate", matches = "true")
class FlywayCurrentDatabaseMigrationIntegrationTest {

    @Test
    void explicitlyApprovedCurrentDatabaseMigratesAndValidates() {
        String database = required("AIMALL_FLYWAY_DRILL_DATABASE");
        if (!"aimall".equals(database)) {
            throw new IllegalArgumentException("current migration drill may only target aimall");
        }
        String username = required("AIMALL_DB_USERNAME");
        String password = required("AIMALL_DB_PASSWORD");
        String url = "jdbc:mysql://127.0.0.1:3306/aimall"
                + "?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai"
                + "&useSSL=false&allowPublicKeyRetrieval=true";

        Flyway flyway = Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("20260716"))
                .validateOnMigrate(true)
                .load();
        flyway.migrate();
        flyway.validate();

        assertEquals("20260722.0900", flyway.info().current().getVersion().toString());
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
