package com.aimall.server.mapper;

import com.aimall.server.entity.PaymentReconciliationItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PaymentReconciliationItemMapper extends BaseMapper<PaymentReconciliationItem> {
    @Update("""
            UPDATE payment_reconciliation_item
            SET resolution_status='CLAIMED', claimed_by=#{operatorId}, claimed_at=NOW(6)
            WHERE id=#{id} AND resolution_status='OPEN'
            """)
    int claim(@Param("id") Long id, @Param("operatorId") Long operatorId);

    @Update("""
            UPDATE payment_reconciliation_item
            SET resolution_status='PENDING_REVIEW'
            WHERE id=#{id} AND resolution_status='CLAIMED' AND claimed_by=#{operatorId}
            """)
    int submitForReview(@Param("id") Long id, @Param("operatorId") Long operatorId);

    @Update("""
            UPDATE payment_reconciliation_item
            SET resolution_status='CLOSED', resolution_note=#{note}, resolved_by=claimed_by,
                resolved_at=NOW(6), reviewed_by=#{reviewerId}, reviewed_at=NOW(6), approval_no=#{approvalNo}
            WHERE id=#{id} AND resolution_status='PENDING_REVIEW' AND claimed_by<>#{reviewerId}
            """)
    int closeAfterReview(@Param("id") Long id, @Param("reviewerId") Long reviewerId,
                         @Param("approvalNo") String approvalNo, @Param("note") String note);

    @Update("""
            UPDATE payment_reconciliation_item
            SET resolution_status='CLAIMED', reviewed_by=#{reviewerId}, reviewed_at=NOW(6),
                resolution_note=#{note}
            WHERE id=#{id} AND resolution_status='PENDING_REVIEW' AND claimed_by<>#{reviewerId}
            """)
    int returnAfterRejection(@Param("id") Long id, @Param("reviewerId") Long reviewerId,
                             @Param("note") String note);

    @Update("""
            UPDATE payment_reconciliation_item
            SET resolution_status='ESCALATED', resolution_note=#{note}, resolved_by=#{operatorId},
                resolved_at=NOW(6)
            WHERE id=#{id} AND resolution_status IN ('OPEN','CLAIMED','PENDING_REVIEW')
            """)
    int escalate(@Param("id") Long id, @Param("operatorId") Long operatorId, @Param("note") String note);

    @Update("""
            UPDATE payment_reconciliation_item SET auto_query_status=#{status}, detail=#{detail}
            WHERE id=#{id} AND auto_query_status='PENDING'
            """)
    int completeAutoQuery(@Param("id") Long id, @Param("status") String status,
                          @Param("detail") String detail);
}
