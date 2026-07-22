package com.aimall.server.mapper;

import com.aimall.server.entity.PaymentReconciliationBatch;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PaymentReconciliationBatchMapper extends BaseMapper<PaymentReconciliationBatch> {
    @Insert("""
            INSERT IGNORE INTO payment_reconciliation_batch
              (batch_no, provider, reconcile_date, status, checked_count, difference_count, started_at)
            VALUES (#{batchNo}, #{provider}, #{reconcileDate}, 'RUNNING', 0, 0, NOW(6))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int reserve(PaymentReconciliationBatch batch);

    @Update("""
            UPDATE payment_reconciliation_batch
            SET status = #{status}, checked_count = #{checkedCount}, difference_count = #{differenceCount},
                finished_at = NOW(6)
            WHERE id = #{id} AND status = 'RUNNING'
            """)
    int finish(@Param("id") Long id, @Param("status") String status,
               @Param("checkedCount") int checkedCount, @Param("differenceCount") int differenceCount);
}
