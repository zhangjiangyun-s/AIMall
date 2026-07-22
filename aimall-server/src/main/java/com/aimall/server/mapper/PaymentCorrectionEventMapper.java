package com.aimall.server.mapper;

import com.aimall.server.entity.PaymentCorrectionEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PaymentCorrectionEventMapper extends BaseMapper<PaymentCorrectionEvent> {
    @Update("""
            UPDATE payment_correction_event
            SET status=#{status}, reviewer_id=#{reviewerId}, approval_no=#{approvalNo},
                review_note=#{reviewNote}, reviewed_at=NOW(6)
            WHERE id=#{id} AND reconciliation_item_id=#{itemId}
              AND status='PENDING_REVIEW' AND operator_id<>#{reviewerId}
            """)
    int review(@Param("id") Long id, @Param("itemId") Long itemId, @Param("status") String status,
               @Param("reviewerId") Long reviewerId, @Param("approvalNo") String approvalNo,
               @Param("reviewNote") String reviewNote);
}
