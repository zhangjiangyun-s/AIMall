package com.aimall.server.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ObservabilityRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(ObservabilityRetentionJob.class);
    private final JdbcTemplate jdbcTemplate;
    private final int auditRetentionDays;

    public ObservabilityRetentionJob(
            JdbcTemplate jdbcTemplate,
            @Value("${aimall.observability.audit-retention-days:180}") int auditRetentionDays
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditRetentionDays = Math.max(30, auditRetentionDays);
    }

    @Scheduled(cron = "${aimall.observability.cleanup-cron:0 40 3 * * *}")
    public void cleanup() {
        int deleted = jdbcTemplate.update(
                "DELETE FROM admin_operation_audit WHERE create_time < DATE_SUB(NOW(), INTERVAL "
                        + auditRetentionDays + " DAY) LIMIT 5000"
        );
        if (deleted > 0) {
            log.info("Observability retention cleanup completed, auditRowsDeleted={}", deleted);
        }
    }
}
