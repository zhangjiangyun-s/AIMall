package com.aimall.server.mapper;

import com.aimall.server.entity.OmsRefundRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OmsRefundRecordMapper extends BaseMapper<OmsRefundRecord> {

    @Insert("""
            INSERT IGNORE INTO oms_refund_record
                (request_id, return_apply_id, order_id, order_sn, refund_channel, refund_status, refund_state, amount,
                 handle_note, retry_count, max_retry, create_time, update_time)
            VALUES
                (#{requestId}, #{returnApplyId}, #{orderId}, #{orderSn}, #{refundChannel}, 'PENDING', 'PENDING', #{amount},
                 #{handleNote}, 0, 8, NOW(), NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int reserve(OmsRefundRecord record);

    @Update("""
            UPDATE oms_refund_record
            SET refund_status = 'CHANNEL_PROCESSING', refund_state = 'PROCESSING',
                retry_count = retry_count + 1,
                failure_reason = NULL,
                update_time = NOW()
            WHERE id = #{id}
              AND retry_count < max_retry
              AND (
                    refund_status = 'PENDING'
                    OR (refund_status = 'RETRY_WAIT' AND (next_retry_time IS NULL OR next_retry_time <= NOW()))
                    OR (refund_status = 'CHANNEL_PROCESSING' AND update_time <= DATE_SUB(NOW(), INTERVAL 5 MINUTE))
                  )
            """)
    int claimChannelCall(@Param("id") Long id);

    @Update("""
            UPDATE oms_refund_record
            SET refund_status = 'CHANNEL_SUCCEEDED', refund_state = 'SUCCEEDED',
                refund_transaction_no = #{transactionNo}, channel_reference = #{transactionNo},
                channel_called_time = NOW(),
                failure_reason = NULL,
                next_retry_time = NULL,
                update_time = NOW()
            WHERE id = #{id} AND refund_status = 'CHANNEL_PROCESSING'
            """)
    int markChannelSucceeded(@Param("id") Long id, @Param("transactionNo") String transactionNo);

    @Update("""
            UPDATE oms_refund_record
            SET refund_status = CASE WHEN retry_count >= max_retry THEN 'FAILED' ELSE 'RETRY_WAIT' END,
                refund_state = CASE WHEN retry_count >= max_retry THEN 'FAILED' ELSE 'RETRY_WAIT' END,
                failure_reason = #{failureReason},
                next_retry_time = #{nextRetryTime},
                update_time = NOW()
            WHERE id = #{id} AND refund_status = 'CHANNEL_PROCESSING'
            """)
    int markChannelFailed(
            @Param("id") Long id,
            @Param("failureReason") String failureReason,
            @Param("nextRetryTime") LocalDateTime nextRetryTime
    );

    @Update("""
            UPDATE oms_refund_record
            SET refund_status = 'REFUND_UNKNOWN', refund_state = 'UNKNOWN', failure_reason = #{reason},
                provider_status = 'UNKNOWN', reconciliation_status = 'OPEN',
                next_retry_time = #{nextQueryTime}, update_time = NOW()
            WHERE id = #{id} AND refund_status = 'CHANNEL_PROCESSING'
            """)
    int markChannelUnknown(@Param("id") Long id, @Param("reason") String reason,
                           @Param("nextQueryTime") LocalDateTime nextQueryTime);

    @Update("""
            UPDATE oms_refund_record
            SET refund_status = 'REFUND_QUERYING', refund_state = 'QUERYING',
                query_retry_count = query_retry_count + 1, query_count = query_count + 1,
                last_query_time = NOW(), last_query_at = NOW(6), update_time = NOW()
            WHERE id = #{id}
              AND (refund_status = 'REFUND_UNKNOWN'
                   OR (refund_status = 'REFUND_QUERYING' AND update_time <= DATE_SUB(NOW(), INTERVAL 2 MINUTE)))
              AND (next_retry_time IS NULL OR next_retry_time <= NOW())
            """)
    int claimRefundQuery(@Param("id") Long id);

    @Update("""
            UPDATE oms_refund_record
            SET refund_status = 'CHANNEL_SUCCEEDED', refund_state = 'SUCCEEDED',
                refund_transaction_no = #{transactionNo}, channel_reference = #{transactionNo},
                provider_status = 'SUCCEEDED', reconciliation_status = 'CLEAR',
                failure_reason = NULL, next_retry_time = NULL, update_time = NOW()
            WHERE id = #{id} AND refund_status = 'REFUND_QUERYING'
            """)
    int markRefundQuerySucceeded(@Param("id") Long id, @Param("transactionNo") String transactionNo);

    @Update("""
            UPDATE oms_refund_record
            SET refund_status = 'RETRY_WAIT', refund_state = 'RETRY_WAIT', provider_status = 'NOT_FOUND',
                failure_reason = 'Refund not found at provider; original idempotent request may be retried',
                next_retry_time = #{nextRetryTime}, update_time = NOW()
            WHERE id = #{id} AND refund_status = 'REFUND_QUERYING'
            """)
    int markRefundQueryNotFound(@Param("id") Long id, @Param("nextRetryTime") LocalDateTime nextRetryTime);

    @Update("""
            UPDATE oms_refund_record
            SET refund_status = 'REFUND_UNKNOWN', refund_state = 'UNKNOWN', provider_status = #{providerStatus},
                reconciliation_status = 'OPEN',
                failure_reason = #{reason}, next_retry_time = #{nextQueryTime}, update_time = NOW()
            WHERE id = #{id} AND refund_status = 'REFUND_QUERYING'
            """)
    int markRefundQueryUnknown(@Param("id") Long id, @Param("providerStatus") String providerStatus,
                               @Param("reason") String reason, @Param("nextQueryTime") LocalDateTime nextQueryTime);

    @Update("""
            UPDATE oms_refund_record
            SET refund_status = 'SUCCEEDED', refund_state = 'SUCCEEDED', reconciliation_status = 'CLEAR',
                finished_time = NOW(), failure_reason = NULL, update_time = NOW()
            WHERE id = #{id} AND refund_status = 'CHANNEL_SUCCEEDED'
            """)
    int markBusinessSucceeded(@Param("id") Long id);

    @Select("""
            SELECT * FROM oms_refund_record
            WHERE id = #{id}
            FOR UPDATE
            """)
    OmsRefundRecord selectByIdForUpdate(@Param("id") Long id);

    @Select("""
            SELECT * FROM oms_refund_record
            WHERE refund_status = 'CHANNEL_SUCCEEDED'
               OR refund_status = 'PENDING'
               OR (refund_status = 'RETRY_WAIT' AND (next_retry_time IS NULL OR next_retry_time <= NOW()))
               OR (refund_status = 'CHANNEL_PROCESSING' AND update_time <= DATE_SUB(NOW(), INTERVAL 5 MINUTE))
               OR (refund_status = 'REFUND_UNKNOWN' AND (next_retry_time IS NULL OR next_retry_time <= NOW()))
               OR (refund_status = 'REFUND_QUERYING' AND update_time <= DATE_SUB(NOW(), INTERVAL 2 MINUTE))
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    List<OmsRefundRecord> listRecoverable(@Param("limit") int limit);

    @Update("""
            UPDATE oms_refund_record
            SET refund_status = 'PENDING', refund_state = 'PENDING', reconciliation_status = 'CLEAR', manual_owner = NULL,
                retry_count = 0,
                manual_retry_count = manual_retry_count + 1,
                next_retry_time = NOW(),
                failure_reason = #{reason},
                closed_time = NULL,
                closed_reason = NULL,
                update_time = NOW()
            WHERE return_apply_id = #{returnApplyId}
              AND refund_status = 'FAILED'
            """)
    int retryFailed(@Param("returnApplyId") Long returnApplyId, @Param("reason") String reason);

    @Update("""
            UPDATE oms_refund_record
            SET refund_status = 'CLOSED', refund_state = 'CLOSED',
                closed_time = NOW(),
                closed_reason = #{reason},
                next_retry_time = NULL,
                update_time = NOW()
            WHERE return_apply_id = #{returnApplyId}
              AND refund_status = 'FAILED'
            """)
    int closeFailed(@Param("returnApplyId") Long returnApplyId, @Param("reason") String reason);

    @Update("""
            UPDATE oms_refund_record SET reconciliation_status='OPEN', update_time=NOW(6)
            WHERE id=#{id} AND reconciliation_status='CLEAR'
            """)
    int markReconciliationOpen(@Param("id") Long id);

    @Update("""
            UPDATE oms_refund_record SET manual_owner=#{operatorId}, update_time=NOW(6)
            WHERE id=#{id} AND reconciliation_status='OPEN' AND manual_owner IS NULL
            """)
    int claimReconciliation(@Param("id") Long id, @Param("operatorId") Long operatorId);

    @Update("""
            UPDATE oms_refund_record SET reconciliation_status='CLEAR', update_time=NOW(6)
            WHERE id=#{id} AND reconciliation_status='OPEN' AND manual_owner IS NOT NULL
            """)
    int clearReconciliation(@Param("id") Long id);

    @Update("""
            UPDATE oms_refund_record SET reconciliation_status='ESCALATED', update_time=NOW(6)
            WHERE id=#{id} AND reconciliation_status='OPEN'
            """)
    int escalateReconciliation(@Param("id") Long id);
}
