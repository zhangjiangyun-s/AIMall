package com.aimall.server.mapper;

import com.aimall.server.entity.OutboxEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {
    @Insert("""
            INSERT IGNORE INTO outbox_event
              (event_id, aggregate_type, aggregate_id, aggregate_version, event_type,
               idempotency_key, payload_json, payload_hash, trace_id, tenant_id, occurred_at_utc, producer_version,
               payload_schema_version, status, retry_count, next_attempt_at, created_at)
            VALUES
              (#{eventId}, #{aggregateType}, #{aggregateId}, #{aggregateVersion}, #{eventType},
               #{idempotencyKey}, CAST(#{payloadJson} AS JSON), #{payloadHash}, #{traceId}, #{tenantId}, #{occurredAtUtc},
               #{producerVersion}, #{payloadSchemaVersion}, 'PENDING', 0, NOW(6), NOW(6))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIgnore(OutboxEvent event);

    @Select("""
            SELECT * FROM outbox_event
            WHERE (status IN ('PENDING', 'RETRY_WAIT')
                   AND (next_attempt_at IS NULL OR next_attempt_at <= NOW(6))
                   AND (lease_until IS NULL OR lease_until < NOW(6)))
               OR (status = 'PROCESSING' AND lease_until < NOW(6))
            ORDER BY id
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<OutboxEvent> listClaimCandidatesForUpdate(@Param("limit") int limit);

    @Update("""
            UPDATE outbox_event
            SET status = 'PROCESSING', lease_owner = #{owner}, lease_until = #{leaseUntil},
                retry_count = retry_count + 1, last_error_code = NULL, last_error_message = NULL
            WHERE id = #{id}
              AND ((status IN ('PENDING', 'RETRY_WAIT') AND (next_attempt_at IS NULL OR next_attempt_at <= NOW(6)))
                OR (status = 'PROCESSING' AND lease_until < NOW(6)))
            """)
    int claim(@Param("id") Long id, @Param("owner") String owner, @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE outbox_event
            SET lease_until = #{leaseUntil}
            WHERE id = #{id} AND status = 'PROCESSING' AND lease_owner = #{owner}
            """)
    int renewLease(@Param("id") Long id, @Param("owner") String owner,
                   @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE outbox_event
            SET status = 'SUCCEEDED', sent_at = NOW(6), lease_owner = NULL, lease_until = NULL,
                next_attempt_at = NULL, last_error_code = NULL, last_error_message = NULL
            WHERE id = #{id} AND status = 'PROCESSING' AND lease_owner = #{owner}
            """)
    int markSucceeded(@Param("id") Long id, @Param("owner") String owner);

    @Update("""
            UPDATE outbox_event
            SET status = 'RETRY_WAIT', next_attempt_at = #{nextAttemptAt}, lease_owner = NULL, lease_until = NULL,
                last_error_code = #{errorCode}, last_error_message = #{errorMessage}
            WHERE id = #{id} AND status = 'PROCESSING' AND lease_owner = #{owner}
            """)
    int markRetry(@Param("id") Long id, @Param("owner") String owner,
                  @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                  @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE outbox_event
            SET status = 'DEAD_LETTER', last_lease_owner = lease_owner,
                lease_owner = NULL, lease_until = NULL, next_attempt_at = NULL,
                last_error_code = #{errorCode}, last_error_message = #{errorMessage}
            WHERE id = #{id} AND status = 'PROCESSING' AND lease_owner = #{owner}
            """)
    int markDeadLetter(@Param("id") Long id, @Param("owner") String owner,
                       @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE outbox_event
            SET status = 'RETRY_WAIT', retry_count = 0, next_attempt_at = NOW(6),
                lease_owner = NULL, lease_until = NULL, last_error_code = NULL, last_error_message = NULL
            WHERE id = #{id} AND status = 'DEAD_LETTER'
            """)
    int retryDeadLetter(@Param("id") Long id);

    @Update("""
            UPDATE outbox_event
            SET status = 'MANUAL_REVIEW', next_attempt_at = NULL,
                lease_owner = NULL, lease_until = NULL,
                last_error_code = 'MANUAL_REVIEW_REQUIRED',
                last_error_message = #{reason}
            WHERE id = #{id} AND status = 'DEAD_LETTER'
            """)
    int moveToManualReview(@Param("id") Long id, @Param("reason") String reason);

    @Update("""
            UPDATE outbox_event
            SET status = 'CANCELLED', next_attempt_at = NULL, lease_owner = NULL, lease_until = NULL,
                last_error_code = 'MANUALLY_CLOSED', last_error_message = #{reason}
            WHERE id = #{id} AND status = 'DEAD_LETTER'
            """)
    int closeDeadLetter(@Param("id") Long id, @Param("reason") String reason);
}
