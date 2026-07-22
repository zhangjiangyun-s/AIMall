package com.aimall.server.integration;

import com.aimall.server.mapper.PmsSkuStockMapper;
import com.aimall.server.mapper.PmsProductMapper;
import com.aimall.server.entity.ProductStockAlert;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.mapper.OmsOrderItemMapper;
import com.aimall.server.mapper.OmsPaymentRecordMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@EnabledIfSystemProperty(named = "aimall.mysql.integration", matches = "true")
class MySqlConcurrencyIntegrationTest {

    private static final String DATABASE = "aimall_it_" + UUID.randomUUID().toString().replace("-", "");
    private static String adminUrl;
    private static String databaseUrl;
    private static String username;
    private static String password;

    @BeforeAll
    static void createDatabase() throws Exception {
        Map<String, String> environment = loadEnvironment();
        username = environment.getOrDefault("AIMALL_DB_USERNAME", "root");
        password = environment.getOrDefault("AIMALL_DB_PASSWORD", "123456");
        adminUrl = "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&characterEncoding=utf-8"
                + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
        databaseUrl = adminUrl.replace("3306/?", "3306/" + DATABASE + "?");

        try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + DATABASE + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }

        String schema = Files.readString(Path.of("src", "main", "resources", "schema.sql"), StandardCharsets.UTF_8)
                .replace(
                        "CREATE DATABASE IF NOT EXISTS aimall DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;",
                        "CREATE DATABASE IF NOT EXISTS `" + DATABASE + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
                )
                .replace("USE aimall;", "USE `" + DATABASE + "`;");
        try (Connection connection = DriverManager.getConnection(databaseUrl, username, password)) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(
                            new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8)),
                            StandardCharsets.UTF_8
                    )
            );
        }
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        if (adminUrl == null) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + DATABASE + "`");
        }
    }

    @Test
    void skuModeReservesOnlySkuInventoryAndNeverTouchesStaleSpuProjection() throws Exception {
        execute("""
                INSERT INTO pms_product
                    (id, category_id, name, product_sn, price, stock, lock_stock, publish_status, delete_status)
                VALUES (9001, 1, '并发测试商品', 'IT-STOCK-1', 100.00, 5, 0, 1, 0)
                """);
        execute("""
                INSERT INTO pms_sku_stock
                    (id, product_id, sku_code, price, stock, lock_stock, status)
                VALUES (9101, 9001, 'IT-SKU-1', 100.00, 5, 0, 1)
                """);

        int skuSuccess = race(12, connection -> update(connection, """
                UPDATE pms_sku_stock
                SET lock_stock = lock_stock + 1
                WHERE id = 9101 AND product_id = 9001 AND stock - lock_stock >= 1 AND status = 1
                  AND EXISTS (
                        SELECT 1 FROM pms_product product
                        WHERE product.id = pms_sku_stock.product_id
                          AND product.delete_status = 0 AND product.publish_status = 1
                      )
                """));

        assertEquals(5, skuSuccess);
        assertEquals(0, queryInt("SELECT lock_stock FROM pms_product WHERE id = 9001"));
        assertEquals(5, queryInt("SELECT lock_stock FROM pms_sku_stock WHERE id = 9101"));
    }

    @Test
    void lastSkuConcurrentReservationAllowsExactlyOneBuyer() throws Exception {
        execute("""
                INSERT INTO pms_product
                    (id, category_id, name, product_sn, price, stock, lock_stock, publish_status, delete_status)
                VALUES (9002, 1, 'IT-LAST-SKU', 'IT-LAST-SKU-1', 100.00, 0, 0, 1, 0)
                """);
        execute("""
                INSERT INTO pms_sku_stock
                    (id, product_id, sku_code, price, stock, lock_stock, status)
                VALUES (9102, 9002, 'IT-LAST-SKU-1', 100.00, 1, 0, 1)
                """);

        int success = race(16, connection -> update(connection, """
                UPDATE pms_sku_stock
                SET lock_stock = lock_stock + 1
                WHERE id = 9102 AND product_id = 9002
                  AND stock - lock_stock >= 1 AND status = 1
                """));

        assertEquals(1, success);
        assertEquals(1, queryInt("SELECT lock_stock FROM pms_sku_stock WHERE id = 9102"));
        assertEquals(0, queryInt("SELECT lock_stock FROM pms_product WHERE id = 9002"));
    }

    @Test
    void inventoryReservationContentionMatrixHasSingleWinnerWithinLatencyGate() throws Exception {
        for (int workers : List.of(2, 10, 50)) {
            long productId = 9050L + workers;
            long skuId = 9150L + workers;
            execute("""
                    INSERT INTO pms_product
                        (id, category_id, name, product_sn, price, stock, lock_stock,
                         publish_status, delete_status)
                    VALUES (%d, 1, 'IT-CONTENTION-%d', 'IT-CONTENTION-%d', 10.00, 0, 0, 1, 0)
                    """.formatted(productId, workers, workers));
            execute("""
                    INSERT INTO pms_sku_stock
                        (id, product_id, sku_code, price, stock, lock_stock, status)
                    VALUES (%d, %d, 'IT-CONTENTION-SKU-%d', 10.00, 1, 0, 1)
                    """.formatted(skuId, productId, workers));

            long startedAt = System.nanoTime();
            int success = race(workers, connection -> update(connection, """
                    UPDATE pms_sku_stock
                    SET lock_stock = lock_stock + 1
                    WHERE id = %d AND product_id = %d
                      AND stock - lock_stock >= 1 AND status = 1
                    """.formatted(skuId, productId)));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertEquals(1, success, "workers=" + workers);
            assertEquals(1, queryInt("SELECT lock_stock FROM pms_sku_stock WHERE id=" + skuId));
            assertTrue(elapsedMillis < Duration.ofSeconds(10).toMillis(),
                    () -> "workers=" + workers + " elapsedMillis=" + elapsedMillis);
        }
    }

    @Test
    void paymentReconciliationMigrationIsIdempotentAndClaimHasSingleOwner() throws Exception {
        Path migration = Path.of(
                "src", "main", "resources", "db", "migration",
                "V20260721_1700__payment_reconciliation_workflow.sql"
        );
        applySqlScript(migration);
        applySqlScript(migration);
        execute("""
                INSERT INTO payment_reconciliation_batch
                    (id,batch_no,provider,reconcile_date,status,checked_count,difference_count,started_at)
                VALUES (9170,'IT-RECON-9170','ALIPAY_SANDBOX','2030-01-01','COMPLETED',1,1,NOW(6))
                """);
        execute("""
                INSERT INTO payment_reconciliation_item
                    (id,batch_id,difference_type,local_status,provider_status,resolution_status,auto_query_status,created_at)
                VALUES (9171,9170,'AMOUNT_MISMATCH','PAID','TRADE_SUCCESS','OPEN','COMPLETED',NOW(6))
                """);

        int claims = race(12, connection -> update(connection, """
                UPDATE payment_reconciliation_item
                SET resolution_status='CLAIMED', claimed_by=CONNECTION_ID(), claimed_at=NOW(6)
                WHERE id=9171 AND resolution_status='OPEN'
                """));

        assertEquals(1, claims);
        assertEquals("CLAIMED", queryString(
                "SELECT resolution_status FROM payment_reconciliation_item WHERE id=9171"));
        assertEquals(1, queryInt("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name='payment_correction_event'
                """));
        assertEquals(6, queryInt("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='oms_refund_record'
                  AND column_name IN ('refund_state','channel_reference','last_query_at','query_count',
                                      'reconciliation_status','manual_owner')
                """));
    }

    @Test
    void preciseMoneyMigrationIsIdempotentAndRecoversPartialCurrencyColumns() throws Exception {
        Path migration = Path.of("src", "main", "resources", "db", "migration",
                "V20260721_1800__precise_money_snapshots.sql");
        applySqlScript(migration);
        execute("ALTER TABLE oms_payment_record DROP COLUMN currency_scale");
        applySqlScript(migration);

        assertEquals(1, queryInt("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='oms_payment_record'
                  AND column_name='currency_scale'
                """));
        assertEquals(5, queryInt("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name IN
                  ('order_money_snapshot_v2','order_item_money_snapshot_v2','payment_money_snapshot_v2',
                   'refund_money_snapshot_v2','return_item_money_snapshot_v2')
                """));
        assertEquals(4, queryInt("""
                SELECT numeric_scale FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='order_money_snapshot_v2'
                  AND column_name='pay_amount'
                """));
        assertEquals(9, queryInt("SELECT COUNT(*) FROM money_precision_assessment"));
        assertEquals(6, queryInt("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND numeric_scale=4 AND
                  ((table_name='pms_product' AND column_name IN ('price_v2','promotion_price_v2','original_price_v2'))
                   OR (table_name='pms_sku_stock' AND column_name IN ('price_v2','promotion_price_v2'))
                   OR (table_name='pms_product_price_rule' AND column_name='price_v2'))
                """));
    }

    @Test
    void knowledgeSourceTrustMigrationIsIdempotentOnExistingDatabase() throws Exception {
        execute("ALTER TABLE knowledge_doc DROP COLUMN source_trust_score");

        applySqlScript(Path.of(
                "src", "main", "resources", "db", "migration",
                "V20260718_1880__knowledge_source_trust_score.sql"
        ));
        applySqlScript(Path.of(
                "src", "main", "resources", "db", "migration",
                "V20260718_1880__knowledge_source_trust_score.sql"
        ));

        assertEquals(1, queryInt("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'knowledge_doc'
                  AND column_name = 'source_trust_score'
                """));
        execute("INSERT INTO knowledge_doc (id, title) VALUES (9998, 'source trust default test')");
        assertEquals(new BigDecimal("0.500"), queryDecimal(
                "SELECT source_trust_score FROM knowledge_doc WHERE id = 9998"
        ));
    }

    @Test
    void memberEmailMigrationIsIdempotentAndOnlyOneActiveCodeCanExist() throws Exception {
        execute("DROP TABLE IF EXISTS ums_email_verification_send_log");
        execute("DROP TABLE IF EXISTS ums_email_verification_code");
        execute("ALTER TABLE ums_member DROP INDEX uk_ums_member_email");
        execute("ALTER TABLE ums_member DROP COLUMN email");
        Path migration = Path.of(
                "src", "main", "resources", "db", "migration",
                "V20260718_1890__member_email_verification.sql"
        );

        applySqlScript(migration);
        applySqlScript(migration);

        assertEquals(1, queryInt("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'ums_member' AND column_name = 'email'
                """));
        int success = race(12, connection -> update(connection, """
                INSERT INTO ums_email_verification_code
                    (email, purpose, code_hash, status, expires_at, last_sent_at)
                VALUES
                    ('mail@example.com', 'REGISTER', REPEAT('a', 64), 'ACTIVE',
                     DATE_ADD(NOW(), INTERVAL 10 MINUTE), NOW())
                """));
        assertEquals(1, success);
        assertEquals(1, queryInt("""
                SELECT COUNT(*) FROM ums_email_verification_code
                WHERE email = 'mail@example.com' AND purpose = 'REGISTER' AND status = 'ACTIVE'
                """));
    }

    @Test
    void outboxContractMigrationIsRecoverableIdempotentAndLeaseClaimIsAtomic() throws Exception {
        applySqlScript(Path.of(
                "src", "main", "resources", "db", "migration",
                "V20260718_1400__payment_outbox_and_attempt_foundation.sql"
        ));
        execute("ALTER TABLE outbox_event ADD COLUMN occurred_at_utc datetime(6) NULL AFTER trace_id");
        execute("""
                INSERT INTO outbox_event
                    (event_id, aggregate_type, aggregate_id, aggregate_version, event_type,
                     idempotency_key, payload_json, trace_id, status, retry_count, next_attempt_at, created_at)
                VALUES
                    ('legacy-event', 'ORDER', '1', 0, 'OrderCreated',
                     'legacy-idempotency', JSON_OBJECT('orderId', 1), 'legacy-trace',
                     'PENDING', 0, NOW(6), NOW(6))
                """);
        Path migration = Path.of(
                "src", "main", "resources", "db", "migration",
                "V20260720_1500__outbox_event_contract.sql"
        );

        applySqlScript(migration);
        applySqlScript(migration);

        assertEquals("NEW", queryString("SELECT status FROM outbox_event WHERE event_id='legacy-event'"));
        assertEquals(3, queryInt("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'outbox_event'
                  AND column_name IN ('occurred_at_utc','producer_version','payload_schema_version')
                """));
        int firstClaims = race(12, connection -> update(connection, """
                UPDATE outbox_event
                SET status='PROCESSING', lease_owner=CONNECTION_ID(),
                    lease_until=DATE_ADD(NOW(6), INTERVAL 30 SECOND), retry_count=retry_count+1
                WHERE event_id='legacy-event' AND status IN ('NEW','RETRY')
                  AND (next_attempt_at IS NULL OR next_attempt_at <= NOW(6))
                """));
        assertEquals(1, firstClaims);

        execute("UPDATE outbox_event SET lease_until=DATE_SUB(NOW(6), INTERVAL 1 SECOND) WHERE event_id='legacy-event'");
        int takeoverClaims = race(12, connection -> update(connection, """
                UPDATE outbox_event
                SET lease_owner=CONNECTION_ID(), lease_until=DATE_ADD(NOW(6), INTERVAL 30 SECOND),
                    retry_count=retry_count+1
                WHERE event_id='legacy-event' AND status='PROCESSING' AND lease_until < NOW(6)
                """));
        assertEquals(1, takeoverClaims);

        int idempotentInserts = race(12, connection -> update(connection, """
                INSERT IGNORE INTO outbox_event
                    (event_id, aggregate_type, aggregate_id, aggregate_version, event_type,
                     idempotency_key, payload_json, trace_id, occurred_at_utc, producer_version,
                     payload_schema_version, status, retry_count, next_attempt_at, created_at)
                VALUES
                    (UUID(), 'ORDER', '2', 1, 'OrderCreated', 'same-business-fact',
                     JSON_OBJECT('orderId', 2), 'trace', UTC_TIMESTAMP(6), 'test/1', 1,
                     'NEW', 0, NOW(6), NOW(6))
                """));
        assertEquals(1, idempotentInserts);
    }

    @Test
    void onlyOneActiveKnowledgeTaskCanExistPerDocumentVersion() throws Exception {
        execute("INSERT INTO knowledge_doc (id, title) VALUES (9901, '知识任务并发测试')");
        execute("""
                INSERT INTO knowledge_doc_version
                    (id, doc_id, version_no, file_name, file_type, source_hash, storage_path)
                VALUES
                    (9951, 9901, 1, 'test.md', 'MD', REPEAT('a', 64), 'storage/test.md')
                """);

        int success = race(12, connection -> update(connection, """
                INSERT INTO knowledge_index_task
                    (task_id, doc_id, doc_version_id, task_type, status)
                VALUES
                    (CONCAT('KT-', UUID()), 9901, 9951, 'PROCESS_DOC_UPLOAD', 'PENDING')
                """));

        assertEquals(1, success);
        assertEquals(1, queryInt("""
                SELECT COUNT(*) FROM knowledge_index_task
                WHERE doc_version_id = 9951
                  AND task_type = 'PROCESS_DOC_UPLOAD'
                  AND status IN ('PENDING', 'DISPATCHING', 'RUNNING', 'RETRY_WAIT')
                """));
    }

    @Test
    void couponIssuanceMemberLimitAndBudgetRemainAtomic() throws Exception {
        execute("""
                INSERT INTO sms_coupon
                    (id, name, amount, min_point, start_time, end_time, status,
                     total_quantity, remaining_quantity, per_member_limit, budget_amount, used_budget)
                VALUES
                    (9201, '并发发行测试券', 3.00, 0.00, NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 1 DAY,
                     1, 3, 3, 2, 10.00, 0.00)
                """);

        int issuanceSuccess = race(12, connection -> update(connection, """
                UPDATE sms_coupon
                SET remaining_quantity = remaining_quantity - 1
                WHERE id = 9201 AND status = 1 AND remaining_quantity > 0
                  AND (receive_start_time IS NULL OR receive_start_time <= NOW())
                  AND (receive_end_time IS NULL OR receive_end_time >= NOW())
                """));
        assertEquals(3, issuanceSuccess);
        assertEquals(0, queryInt("SELECT remaining_quantity FROM sms_coupon WHERE id = 9201"));

        execute("UPDATE sms_coupon SET remaining_quantity = 10 WHERE id = 9201");
        int memberClaims = race(8, MySqlConcurrencyIntegrationTest::claimCouponInTransaction);
        assertEquals(2, memberClaims);
        assertEquals(2, queryInt("SELECT COUNT(*) FROM ums_member_coupon WHERE member_id = 9301 AND coupon_id = 9201"));
        assertEquals(8, queryInt("SELECT remaining_quantity FROM sms_coupon WHERE id = 9201"));

        int budgetSuccess = race(12, connection -> update(connection, """
                UPDATE sms_coupon
                SET used_budget = used_budget + 3.00
                WHERE id = 9201 AND (budget_amount IS NULL OR used_budget + 3.00 <= budget_amount)
                """));
        assertEquals(3, budgetSuccess);
        assertEquals(new BigDecimal("9.00"), queryDecimal("SELECT used_budget FROM sms_coupon WHERE id = 9201"));
    }

    @Test
    void returnReservationPreventsTheSameQuantityFromBeingShipped() throws Exception {
        execute("""
                INSERT INTO oms_order_item
                    (id, order_id, order_sn, product_id, product_name, product_price, product_quantity, real_amount)
                VALUES (9401, 9400, 'IT-ORDER-9400', 9001, '并发测试商品', 100.00, 3, 300.00)
                """);

        int returnSuccess = race(10, connection -> update(connection, """
                UPDATE oms_order_item
                SET return_reserved_quantity = return_reserved_quantity + 1
                WHERE id = 9401 AND order_id = 9400
                  AND product_quantity - return_reserved_quantity - refunded_quantity >= 1
                """));
        int shipmentSuccess = race(10, connection -> update(connection, """
                UPDATE oms_order_item
                SET shipped_quantity = shipped_quantity + 1
                WHERE id = 9401 AND order_id = 9400
                  AND product_quantity - shipped_quantity - return_reserved_quantity - refunded_quantity >= 1
                """));

        assertEquals(3, returnSuccess);
        assertEquals(0, shipmentSuccess);
        assertEquals(3, queryInt("SELECT return_reserved_quantity FROM oms_order_item WHERE id = 9401"));
        assertEquals(0, queryInt("SELECT shipped_quantity FROM oms_order_item WHERE id = 9401"));
    }

    @Test
    void duplicateActiveReturnApplicationsAreRejectedByTheDatabase() throws Exception {
        int success = race(12, connection -> update(connection, """
                INSERT INTO oms_order_return_apply
                    (order_id, order_sn, member_id, status, type, reason, return_amount)
                VALUES (9501, 'IT-ORDER-9501', 9500, 0, 'REFUND', '并发测试', 10.00)
                """));

        assertEquals(1, success);
        assertEquals(1, queryInt("""
                SELECT COUNT(*) FROM oms_order_return_apply
                WHERE order_id = 9501 AND type = 'REFUND' AND status IN (0, 1, 5)
                """));
    }

    @Test
    void channelSuccessSurvivesLocalRefundTransactionFailure() throws Exception {
        execute("""
                INSERT INTO oms_refund_record
                    (id, request_id, return_apply_id, order_id, order_sn, refund_channel,
                     refund_status, amount, retry_count, max_retry)
                VALUES
                    (9601, 'IT-REFUND-9601', 9501, 9501, 'IT-ORDER-9501', 'SIMULATED',
                     'PENDING', 10.00, 0, 8)
                """);
        assertEquals(1, executeUpdate("""
                UPDATE oms_refund_record SET refund_status = 'CHANNEL_PROCESSING'
                WHERE id = 9601 AND refund_status = 'PENDING'
                """));
        assertEquals(1, executeUpdate("""
                UPDATE oms_refund_record
                SET refund_status = 'CHANNEL_SUCCEEDED', refund_transaction_no = 'IT-TX-9601'
                WHERE id = 9601 AND refund_status = 'CHANNEL_PROCESSING'
                """));

        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            update(connection, """
                    UPDATE oms_refund_record SET refund_status = 'SUCCEEDED'
                    WHERE id = 9601 AND refund_status = 'CHANNEL_SUCCEEDED'
                    """);
            connection.rollback();
        }

        assertEquals("CHANNEL_SUCCEEDED", queryString("SELECT refund_status FROM oms_refund_record WHERE id = 9601"));
        assertEquals(1, executeUpdate("""
                UPDATE oms_refund_record SET refund_status = 'SUCCEEDED'
                WHERE id = 9601 AND refund_status = 'CHANNEL_SUCCEEDED'
                """));
        assertEquals("SUCCEEDED", queryString("SELECT refund_status FROM oms_refund_record WHERE id = 9601"));
    }

    @Test
    void fullRefundUsesTheOriginalRefundedAmountWhenDerivingStatus() throws Exception {
        execute("""
                INSERT INTO oms_order
                    (id, member_id, order_sn, total_amount, pay_amount, status, refund_status, refunded_amount)
                VALUES (9700, 9700, 'IT-ORDER-9700', 100.00, 100.00, 1, 'NONE', 0.00)
                """);
        execute("""
                INSERT INTO oms_payment_record
                    (id, order_id, order_sn, pay_channel, pay_status, amount, refunded_amount)
                VALUES (9701, 9700, 'IT-ORDER-9700', 'ALIPAY_SANDBOX', 'PAID', 100.00, 0.00)
                """);

        try (SqlSession session = skuMapperSessionFactory().openSession(true)) {
            assertEquals(1, session.getMapper(OmsPaymentRecordMapper.class)
                    .addRefund(9700L, new BigDecimal("100.00")));
            assertEquals(1, session.getMapper(OmsOrderMapper.class)
                    .addRefund(9700L, new BigDecimal("100.00"), java.time.LocalDateTime.now()));
        }

        assertEquals("REFUNDED", queryString(
                "SELECT pay_status FROM oms_payment_record WHERE order_id = 9700"));
        assertEquals("FULL_REFUNDED", queryString(
                "SELECT refund_status FROM oms_order WHERE id = 9700"));
    }

    @Test
    void partialShipmentOnlyAutoReceivesAfterRemainingQuantityIsRefundedOrShipped() throws Exception {
        execute("""
                INSERT INTO oms_order
                    (id, member_id, order_sn, status, inventory_reservation_status)
                VALUES (9701, 9700, 'IT-ORDER-9701', 2, 'DEDUCTED')
                """);
        execute("""
                INSERT INTO oms_order_item
                    (id, order_id, order_sn, product_id, product_name, product_price,
                     product_quantity, shipped_quantity, real_amount)
                VALUES (9702, 9701, 'IT-ORDER-9701', 9001, '部分发货测试商品', 10.00, 2, 1, 20.00)
                """);
        execute("""
                INSERT INTO oms_shipment
                    (id, shipment_sn, order_id, order_sn, carrier_code, carrier_name,
                     tracking_no, status, shipped_at, delivered_at)
                VALUES (9703, 'IT-SHIP-9703', 9701, 'IT-ORDER-9701', 'TEST', '测试物流',
                        'IT-TRACK-9703', 'DELIVERED', NOW() - INTERVAL 10 DAY, NOW() - INTERVAL 8 DAY)
                """);

        assertEquals(0, executeUpdate(autoReceiveSql(9701)));
        assertEquals(2, queryInt("SELECT status FROM oms_order WHERE id = 9701"));

        execute("UPDATE oms_order_item SET refunded_quantity = 1 WHERE id = 9702");
        assertEquals(1, executeUpdate(autoReceiveSql(9701)));
        assertEquals(3, queryInt("SELECT status FROM oms_order WHERE id = 9701"));
    }

    @Test
    void adminSkuUpdatePreservesConcurrentLockStockAndDisableUsesCas() throws Exception {
        execute("""
                INSERT INTO pms_product
                    (id, category_id, name, product_sn, price, stock, lock_stock, publish_status, delete_status)
                VALUES (9801, 1, 'SKU 管理并发测试商品', 'IT-STOCK-9801', 10.00, 10, 0, 1, 0)
                """);
        execute("""
                INSERT INTO pms_sku_stock
                    (id, product_id, sku_code, price, stock, lock_stock, low_stock, status)
                VALUES (9802, 9801, 'IT-SKU-9802', 10.00, 10, 0, 1, 1)
                """);

        assertEquals(1, executeUpdate("""
                UPDATE pms_sku_stock SET lock_stock = lock_stock + 1
                WHERE id = 9802 AND product_id = 9801 AND stock - lock_stock >= 1
                """));
        assertEquals(1, executeUpdate("""
                UPDATE pms_sku_stock
                SET stock = stock - 1, lock_stock = lock_stock - 1, sale = sale + 1
                WHERE id = 9802 AND product_id = 9801 AND stock >= 1 AND lock_stock >= 1
                """));
        try (SqlSession session = skuMapperSessionFactory().openSession(true)) {
            PmsSkuStockMapper mapper = session.getMapper(PmsSkuStockMapper.class);
            assertEquals(1, mapper.updateAdminFields(new PmsSkuStockMapper.AdminUpdate(
                    9802L, 9801L, "IT-SKU-9802", new BigDecimal("9.00"), null,
                    1, null, null, 1,
                    false, true, false, false, false, false, false
            )));
            assertEquals(1L, mapper.countAllByProductId(9801L));
            assertEquals(9, mapper.sumEnabledAvailableStock(9801L));
        }

        assertEquals(9, queryInt("SELECT stock FROM pms_sku_stock WHERE id = 9802"));
        assertEquals(1, queryInt("SELECT sale FROM pms_sku_stock WHERE id = 9802"));
        assertEquals(1, executeUpdate("""
                UPDATE pms_sku_stock SET lock_stock = lock_stock + 1
                WHERE id = 9802 AND product_id = 9801 AND stock - lock_stock >= 1
                """));
        assertEquals(0, executeUpdate("""
                UPDATE pms_sku_stock SET status = 0
                WHERE id = 9802 AND product_id = 9801 AND lock_stock = 0
                """));
        assertEquals(1, queryInt("SELECT status FROM pms_sku_stock WHERE id = 9802"));
    }

    @Test
    void failedRefundSupportsExplicitRetryAndTerminalClose() throws Exception {
        execute("""
                INSERT INTO oms_refund_record
                    (id, request_id, return_apply_id, order_id, order_sn, refund_channel,
                     refund_status, amount, retry_count, max_retry, failure_reason)
                VALUES
                    (9901, 'IT-REFUND-9901', 9902, 9903, 'IT-ORDER-9903', 'SIMULATED',
                     'FAILED', 10.00, 8, 8, 'channel failed')
                """);

        assertEquals(1, executeUpdate("""
                UPDATE oms_refund_record
                SET refund_status = 'PENDING', retry_count = 0,
                    manual_retry_count = manual_retry_count + 1,
                    next_retry_time = NOW(), failure_reason = '人工重试', update_time = NOW()
                WHERE return_apply_id = 9902 AND refund_status = 'FAILED'
                """));
        assertEquals("PENDING", queryString("SELECT refund_status FROM oms_refund_record WHERE id = 9901"));
        assertEquals(1, queryInt("SELECT manual_retry_count FROM oms_refund_record WHERE id = 9901"));

        execute("UPDATE oms_refund_record SET refund_status = 'FAILED', retry_count = 8 WHERE id = 9901");
        assertEquals(1, executeUpdate("""
                UPDATE oms_refund_record
                SET refund_status = 'CLOSED', closed_time = NOW(), closed_reason = '人工终止',
                    next_retry_time = NULL, update_time = NOW()
                WHERE return_apply_id = 9902 AND refund_status = 'FAILED'
                """));
        assertEquals("CLOSED", queryString("SELECT refund_status FROM oms_refund_record WHERE id = 9901"));
    }

    @Test
    void outboxClaimAndStaleLeaseTakeoverRemainSingleOwnerAcrossInstances() throws Exception {
        execute("""
                CREATE TABLE IF NOT EXISTS outbox_event_it (
                  id bigint NOT NULL AUTO_INCREMENT,
                  status varchar(24) NOT NULL,
                  retry_count int NOT NULL DEFAULT 0,
                  next_attempt_at datetime(6) DEFAULT NULL,
                  lease_owner varchar(128) DEFAULT NULL,
                  lease_until datetime(6) DEFAULT NULL,
                  PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);
        execute("INSERT INTO outbox_event_it (id,status,next_attempt_at) VALUES (1,'PENDING',NOW(6))");

        int firstClaims = race(12, connection -> update(connection, """
                UPDATE outbox_event_it
                SET status='PROCESSING', lease_owner=CONNECTION_ID(),
                    lease_until=DATE_ADD(NOW(6), INTERVAL 60 SECOND), retry_count=retry_count+1
                WHERE id=1 AND status IN ('PENDING','RETRY_WAIT') AND next_attempt_at <= NOW(6)
                """));
        assertEquals(1, firstClaims);
        assertEquals(1, queryInt("SELECT retry_count FROM outbox_event_it WHERE id=1"));

        execute("UPDATE outbox_event_it SET lease_until=DATE_SUB(NOW(6), INTERVAL 1 SECOND)");
        int takeoverClaims = race(12, connection -> update(connection, """
                UPDATE outbox_event_it
                SET lease_owner=CONNECTION_ID(), lease_until=DATE_ADD(NOW(6), INTERVAL 60 SECOND),
                    retry_count=retry_count+1
                WHERE id=1 AND status='PROCESSING' AND lease_until < NOW(6)
                """));
        assertEquals(1, takeoverClaims);
        assertEquals(2, queryInt("SELECT retry_count FROM outbox_event_it WHERE id=1"));
    }

    @Test
    void concurrentLatePaymentCallbacksCreateOnlyOneRefundCase() throws Exception {
        execute("""
                CREATE TABLE IF NOT EXISTS late_payment_case_it (
                  id bigint NOT NULL AUTO_INCREMENT,
                  payment_id bigint NOT NULL,
                  refund_request_id varchar(64) NOT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_late_payment_it_payment (payment_id),
                  UNIQUE KEY uk_late_payment_it_request (refund_request_id)
                ) ENGINE=InnoDB
                """);
        int success = race(12, connection -> update(connection, """
                INSERT IGNORE INTO late_payment_case_it (payment_id,refund_request_id)
                VALUES (5,'LATE-5')
                """));
        assertEquals(1, success);
        assertEquals(1, queryInt("SELECT COUNT(*) FROM late_payment_case_it WHERE payment_id=5"));
    }

    @Test
    void lowStockAlertsUseAvailableStockForSkuAndStandaloneProducts() throws Exception {
        execute("""
                INSERT INTO pms_product
                    (id, category_id, name, product_sn, price, stock, lock_stock, low_stock, publish_status, delete_status)
                VALUES
                    (9910, 1, 'IT-STANDALONE-LOW', 'IT-LOW-9910', 10.00, 3, 1, 2, 1, 0),
                    (9911, 1, 'IT-SKU-LOW', 'IT-LOW-9911', 10.00, 0, 0, 0, 1, 0),
                    (9912, 1, 'IT-SKU-ENOUGH', 'IT-LOW-9912', 10.00, 0, 0, 0, 1, 0)
                """);
        execute("""
                INSERT INTO pms_sku_stock
                    (id, product_id, sku_code, price, stock, lock_stock, low_stock, status)
                VALUES
                    (9921, 9911, 'IT-SKU-LOW-1', 10.00, 10, 8, 2, 1),
                    (9922, 9912, 'IT-SKU-ENOUGH-1', 10.00, 10, 5, 2, 1)
                """);

        try (SqlSession session = skuMapperSessionFactory().openSession(true)) {
            List<ProductStockAlert> alerts = session.getMapper(PmsSkuStockMapper.class).listLowStockAlerts(200);
            assertTrue(alerts.stream().anyMatch(item -> Long.valueOf(9910L).equals(item.getProductId())
                    && item.getSkuId() == null && item.getAvailableStock() == 2));
            assertTrue(alerts.stream().anyMatch(item -> Long.valueOf(9911L).equals(item.getProductId())
                    && Long.valueOf(9921L).equals(item.getSkuId()) && item.getAvailableStock() == 2));
            assertTrue(alerts.stream().noneMatch(item -> Long.valueOf(9912L).equals(item.getProductId())));
        }
    }

    @Test
    void productPublishTransitionRequiresAnEnabledSkuOnceSkuModeExists() throws Exception {
        execute("""
                INSERT INTO pms_product
                    (id, category_id, name, product_sn, price, stock, lock_stock,
                     publish_status, verify_status, delete_status)
                VALUES
                    (9930, 1, 'IT-PUBLISH-STATE', 'IT-PUBLISH-9930', 10.00, 0, 0, 0, 1, 0)
                """);
        execute("""
                INSERT INTO pms_sku_stock
                    (id, product_id, sku_code, price, stock, lock_stock, low_stock, status)
                VALUES
                    (9931, 9930, 'IT-PUBLISH-SKU-1', 10.00, 10, 0, 2, 0)
                """);

        try (SqlSession session = skuMapperSessionFactory().openSession(true)) {
            PmsProductMapper mapper = session.getMapper(PmsProductMapper.class);
            assertEquals(0, mapper.transitionPublishStatus(9930L, 0, 1));

            execute("UPDATE pms_sku_stock SET status = 1 WHERE id = 9931");
            assertEquals(1, mapper.transitionPublishStatus(9930L, 0, 1));
            assertEquals(1, queryInt("SELECT publish_status FROM pms_product WHERE id = 9930"));

            assertEquals(1, mapper.transitionPublishStatus(9930L, 1, 0));
            assertEquals(0, queryInt("SELECT publish_status FROM pms_product WHERE id = 9930"));
        }
    }

    @Test
    void activityPurchaseLimitCountsOnlyNonClosedOrdersWithinRuleWindow() throws Exception {
        execute("INSERT INTO ums_member (id, username, password, member_level) VALUES (9940, 'it-price-member', 'x', 'GOLD')");
        execute("""
                INSERT INTO pms_product
                    (id, category_id, name, product_sn, price, stock, publish_status, delete_status)
                VALUES (9941, 1, 'IT-PRICE-RULE', 'IT-PRICE-9941', 100.00, 10, 1, 0)
                """);
        execute("""
                INSERT INTO pms_product_price_rule
                    (id, product_id, rule_type, rule_name, price, per_member_limit,
                     status, start_time, end_time)
                VALUES
                    (9942, 9941, 'ACTIVITY', 'IT-LIMIT-3', 80.00, 3, 1,
                     NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 1 DAY)
                """);
        execute("""
                INSERT INTO oms_order (id, member_id, order_sn, status, create_time)
                VALUES
                    (9943, 9940, 'IT-PRICE-ORDER-1', 1, NOW()),
                    (9944, 9940, 'IT-PRICE-ORDER-2', 4, NOW())
                """);
        execute("""
                INSERT INTO oms_order_item
                    (id, order_id, order_sn, product_id, product_name, product_price,
                     product_quantity, real_amount)
                VALUES
                    (9945, 9943, 'IT-PRICE-ORDER-1', 9941, 'IT-PRICE-RULE', 80.00, 2, 160.00),
                    (9946, 9944, 'IT-PRICE-ORDER-2', 9941, 'IT-PRICE-RULE', 80.00, 5, 400.00)
                """);

        try (SqlSession session = skuMapperSessionFactory().openSession(true)) {
            int purchased = session.getMapper(OmsOrderItemMapper.class).sumPurchasedWithoutSku(
                    9940L, 9941L, java.time.LocalDateTime.now().minusDays(1),
                    java.time.LocalDateTime.now().plusDays(1)
            );
            assertEquals(2, purchased);
        }
    }

    private static String autoReceiveSql(long orderId) {
        return """
                UPDATE oms_order order_row
                SET status = 3, confirm_status = 1, receive_time = NOW(), modify_time = NOW()
                WHERE order_row.id = %d AND order_row.delete_status = 0 AND order_row.status = 2
                  AND EXISTS (SELECT 1 FROM oms_shipment shipment WHERE shipment.order_id = order_row.id)
                  AND NOT EXISTS (
                        SELECT 1 FROM oms_shipment shipment
                        WHERE shipment.order_id = order_row.id
                          AND (shipment.status <> 'DELIVERED' OR shipment.delivered_at IS NULL
                               OR shipment.delivered_at > NOW() - INTERVAL 7 DAY)
                      )
                  AND NOT EXISTS (
                        SELECT 1 FROM oms_order_item item
                        WHERE item.order_id = order_row.id
                          AND (
                                item.return_reserved_quantity > 0
                                OR item.shipped_quantity < item.product_quantity - item.refunded_quantity
                              )
                      )
                """.formatted(orderId);
    }

    private static SqlSessionFactory skuMapperSessionFactory() {
        UnpooledDataSource dataSource = new UnpooledDataSource(
                "com.mysql.cj.jdbc.Driver", databaseUrl, username, password
        );
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration(
                new Environment("mysql-integration", new JdbcTransactionFactory(), dataSource)
        );
        configuration.addMapper(PmsSkuStockMapper.class);
        configuration.addMapper(PmsProductMapper.class);
        configuration.addMapper(OmsPaymentRecordMapper.class);
        configuration.addMapper(OmsOrderMapper.class);
        configuration.addMapper(OmsOrderItemMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static int claimCouponInTransaction(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try {
            int reserved = update(connection, """
                    UPDATE sms_coupon
                    SET remaining_quantity = remaining_quantity - 1
                    WHERE id = 9201 AND status = 1 AND remaining_quantity > 0
                    """);
            if (reserved != 1) {
                connection.rollback();
                return 0;
            }
            int inserted = update(connection, """
                    INSERT INTO ums_member_coupon
                        (member_id, coupon_id, coupon_name, claim_no, status, create_time)
                    SELECT 9301, coupon.id, coupon.name, COALESCE(MAX(member_coupon.claim_no), 0) + 1, 0, NOW()
                    FROM sms_coupon coupon
                    LEFT JOIN ums_member_coupon member_coupon
                      ON member_coupon.coupon_id = coupon.id AND member_coupon.member_id = 9301
                    WHERE coupon.id = 9201
                    GROUP BY coupon.id, coupon.name, coupon.per_member_limit
                    HAVING COUNT(member_coupon.id) < coupon.per_member_limit
                    """);
            if (inserted != 1) {
                connection.rollback();
                return 0;
            }
            connection.commit();
            return 1;
        } catch (SQLException exception) {
            connection.rollback();
            if ("23000".equals(exception.getSQLState()) || "40001".equals(exception.getSQLState())) {
                return 0;
            }
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static int race(int workers, SqlAction action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                futures.add(executor.submit(() -> {
                    try (Connection connection = connection()) {
                        ready.countDown();
                        assertTrue(start.await(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS));
                        try {
                            return action.execute(connection);
                        } catch (SQLException exception) {
                            if ("23000".equals(exception.getSQLState()) || "40001".equals(exception.getSQLState())) {
                                return 0;
                            }
                            throw exception;
                        }
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "数据库竞争线程未按时就绪");
            start.countDown();
            int success = 0;
            for (Future<Integer> future : futures) {
                success += future.get(20, TimeUnit.SECONDS);
            }
            return success;
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static int update(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            return statement.executeUpdate();
        }
    }

    private static void execute(String sql) throws SQLException {
        if (executeUpdate(sql) < 0) {
            fail("SQL execution failed");
        }
    }

    private static void applySqlScript(Path path) throws Exception {
        String script = Files.readString(path, StandardCharsets.UTF_8);
        try (Connection connection = connection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(
                            new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)),
                            StandardCharsets.UTF_8
                    )
            );
        }
    }

    private static int executeUpdate(String sql) throws SQLException {
        try (Connection connection = connection()) {
            return update(connection, sql);
        }
    }

    private static int queryInt(String sql) throws SQLException {
        return ((Number) queryOne(sql)).intValue();
    }

    private static BigDecimal queryDecimal(String sql) throws SQLException {
        return (BigDecimal) queryOne(sql);
    }

    private static String queryString(String sql) throws SQLException {
        return String.valueOf(queryOne(sql));
    }

    private static Object queryOne(String sql) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next());
            return resultSet.getObject(1);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(databaseUrl, username, password);
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

    @FunctionalInterface
    private interface SqlAction {
        int execute(Connection connection) throws SQLException;
    }
}
