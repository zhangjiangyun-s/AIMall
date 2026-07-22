package com.aimall.server.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class OperationalMetricsService {
    private static final Logger log = LoggerFactory.getLogger(OperationalMetricsService.class);
    private final JdbcTemplate jdbcTemplate;
    private final Map<String, AtomicLong> gauges = new LinkedHashMap<>();

    public OperationalMetricsService(JdbcTemplate jdbcTemplate, MeterRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        register(registry, "aimall_payment_unknown", "Payments with an unknown provider result");
        register(registry, "aimall_payment_reconciliation_open", "Open payment reconciliation differences");
        register(registry, "aimall_refund_unresolved", "Refunds requiring recovery or manual resolution");
        register(registry, "aimall_late_payment_unresolved", "Late payment cases not resolved");
        register(registry, "aimall_outbox_dead_letter", "Outbox events in dead letter");
        register(registry, "aimall_knowledge_task_dead_letter", "Knowledge tasks in dead letter");
        register(registry, "aimall_vector_delete_pending", "Vectors waiting for deletion");
        register(registry, "aimall_vector_delete_dead_letter", "Vector deletions exhausted retries");
        register(registry, "aimall_inventory_invalid", "SPU or SKU rows violating stock invariants");
        register(registry, "aimall_vector_consistency_mismatch", "Active chunks missing synchronized vectors");
        register(registry, "aimall_login_failures_5m", "Failed member and admin logins in the last five minutes");
        register(registry, "aimall_payments_succeeded_24h", "Successful payments in the last 24 hours");
        register(registry, "aimall_orders_closed_24h", "Orders closed in the last 24 hours");
        register(registry, "aimall_refunds_succeeded_24h", "Successful refunds in the last 24 hours");
        register(registry, "aimall_rag_retrieval_score_milli", "Latest average knowledge retrieval quality score multiplied by 1000");
        register(registry, "aimall_mysql_slow_queries_total", "MySQL global slow query count");
        register(registry, "aimall_mysql_transaction_rollbacks_total", "MySQL transaction rollback count");
        register(registry, "aimall_mysql_row_lock_waits_total", "MySQL InnoDB row lock wait count");
        register(registry, "aimall_mysql_row_lock_time_max_ms", "Conservative maximum observed MySQL row lock wait in milliseconds");
        register(registry, "aimall_observability_query_failures", "Operational metric SQL query failures during the latest refresh");
    }

    @Scheduled(fixedDelayString = "${aimall.observability.refresh-ms:30000}")
    public void refresh() {
        update("aimall_observability_query_failures", 0);
        update("aimall_payment_unknown", count("SELECT COUNT(*) FROM oms_payment_record WHERE payment_state IN ('UNKNOWN','CLOSE_UNKNOWN')"));
        update("aimall_payment_reconciliation_open", count("SELECT COUNT(*) FROM payment_reconciliation_item WHERE resolution_status = 'OPEN'"));
        update("aimall_refund_unresolved", count("SELECT COUNT(*) FROM oms_refund_record WHERE refund_status IN ('FAILED','REFUND_UNKNOWN')"));
        update("aimall_late_payment_unresolved", count("SELECT COUNT(*) FROM late_payment_case WHERE status NOT IN ('REFUNDED','CLOSED')"));
        update("aimall_outbox_dead_letter", count("SELECT COUNT(*) FROM outbox_event WHERE status = 'DEAD_LETTER'"));
        update("aimall_knowledge_task_dead_letter", count("SELECT COUNT(*) FROM knowledge_index_task WHERE status = 'DEAD_LETTER'"));
        update("aimall_vector_delete_pending", count("SELECT COUNT(*) FROM knowledge_chunk WHERE embedding_sync_status = 'DELETE_PENDING'"));
        update("aimall_vector_delete_dead_letter", count("SELECT COUNT(*) FROM knowledge_chunk WHERE embedding_sync_status = 'DELETE_DEAD'"));
        update("aimall_inventory_invalid", count("""
                SELECT
                    (SELECT COUNT(*) FROM pms_product WHERE stock < 0 OR lock_stock < 0 OR stock < lock_stock)
                  + (SELECT COUNT(*) FROM pms_sku_stock WHERE stock < 0 OR lock_stock < 0 OR stock < lock_stock)
                """));
        update("aimall_vector_consistency_mismatch", count("SELECT COUNT(*) FROM knowledge_chunk WHERE status = 'ACTIVE' AND embedding_sync_status <> 'SYNCED'"));
        update("aimall_login_failures_5m", count("""
                SELECT
                    (SELECT COUNT(*) FROM ums_member_login_history WHERE success = 0 AND create_time >= DATE_SUB(NOW(), INTERVAL 5 MINUTE))
                  + (SELECT COUNT(*) FROM ums_admin_login_attempt WHERE success = 0 AND create_time >= DATE_SUB(NOW(), INTERVAL 5 MINUTE))
                """));
        update("aimall_payments_succeeded_24h", count("SELECT COUNT(*) FROM oms_payment_record WHERE payment_state = 'PAID' AND update_time >= DATE_SUB(NOW(), INTERVAL 1 DAY)"));
        update("aimall_orders_closed_24h", count("SELECT COUNT(*) FROM oms_order WHERE status = 4 AND modify_time >= DATE_SUB(NOW(), INTERVAL 1 DAY)"));
        update("aimall_refunds_succeeded_24h", count("SELECT COUNT(*) FROM oms_refund_record WHERE refund_status = 'SUCCEEDED' AND update_time >= DATE_SUB(NOW(), INTERVAL 1 DAY)"));
        update("aimall_rag_retrieval_score_milli", decimalGauge("SELECT COALESCE(AVG(retrieval_score), 0) FROM knowledge_quality_report"));
        update("aimall_mysql_slow_queries_total", mysqlStatus("Slow_queries"));
        update("aimall_mysql_transaction_rollbacks_total", mysqlStatus("Com_rollback"));
        update("aimall_mysql_row_lock_waits_total", mysqlStatus("Innodb_row_lock_waits"));
        update("aimall_mysql_row_lock_time_max_ms", mysqlStatus("Innodb_row_lock_time_max"));
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> result = new LinkedHashMap<>();
        gauges.forEach((name, value) -> result.put(name, value.get()));
        return result;
    }

    private void register(MeterRegistry registry, String name, String description) {
        AtomicLong value = new AtomicLong();
        gauges.put(name, value);
        Gauge.builder(name, value, AtomicLong::get).description(description).register(registry);
    }

    private void update(String name, long value) {
        gauges.get(name).set(Math.max(0, value));
    }

    private long count(String sql) {
        try {
            Long value = jdbcTemplate.queryForObject(sql, Long.class);
            return value == null ? 0 : value;
        } catch (Exception exception) {
            gauges.get("aimall_observability_query_failures").incrementAndGet();
            log.warn("Operational metric query failed, metric={}", metricNameFor(sql));
            return 0;
        }
    }

    private long mysqlStatus(String variable) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = ?",
                    (resultSet, rowNum) -> Long.parseLong(resultSet.getString(1)),
                    variable
            );
        } catch (Exception exception) {
            log.debug("MySQL status metric unavailable, variable={}", variable);
            return 0;
        }
    }

    private long decimalGauge(String sql) {
        try {
            Number value = jdbcTemplate.queryForObject(sql, Number.class);
            return value == null ? 0 : Math.round(value.doubleValue() * 1000);
        } catch (Exception exception) {
            gauges.get("aimall_observability_query_failures").incrementAndGet();
            return 0;
        }
    }

    private String metricNameFor(String sql) {
        return Integer.toHexString(sql.hashCode());
    }
}
