package com.aimall.server.mapper;

import com.aimall.server.entity.OmsPaymentRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface OmsPaymentRecordMapper extends BaseMapper<OmsPaymentRecord> {

    @Update("""
            UPDATE oms_payment_record
            SET pay_status = 'LATE_PAYMENT', payment_state = 'LATE_PAYMENT', paid_amount = #{amount},
                transaction_no = #{transactionNo}, provider_reference = #{transactionNo},
                pay_time = #{paidAt}, callback_time = #{paidAt}, raw_callback = #{rawCallback}, update_time = #{paidAt}
            WHERE id = #{id} AND payment_state IN ('CLOSED', 'CLOSE_UNKNOWN', 'CLOSING')
            """)
    int markLatePayment(@Param("id") Long id, @Param("amount") java.math.BigDecimal amount,
                        @Param("transactionNo") String transactionNo, @Param("paidAt") java.time.LocalDateTime paidAt,
                        @Param("rawCallback") String rawCallback);

    @Update("""
            UPDATE oms_payment_record
            SET pay_status = 'REFUNDED', payment_state = 'LATE_REFUNDED', refunded_amount = amount, update_time = NOW(6)
            WHERE id = #{id} AND payment_state = 'LATE_PAYMENT'
            """)
    int markLatePaymentRefunded(@Param("id") Long id);

    @Update("""
            UPDATE oms_payment_record
            SET pay_status = 'PAID', payment_state = 'PAID', paid_amount = #{amount},
                transaction_no = #{transactionNo}, provider_reference = #{transactionNo},
                pay_time = #{paidAt}, callback_time = #{paidAt}, raw_callback = #{rawCallback}, update_time = #{paidAt}
            WHERE order_id = #{orderId} AND payment_state <> 'PAID'
            """)
    int markAlipayPaid(@Param("orderId") Long orderId, @Param("amount") java.math.BigDecimal amount,
                       @Param("transactionNo") String transactionNo, @Param("paidAt") java.time.LocalDateTime paidAt,
                       @Param("rawCallback") String rawCallback);

    @Select("""
            SELECT payment.* FROM oms_payment_record payment
            JOIN oms_order order_row ON order_row.id = payment.order_id
            WHERE order_row.status = 0
              AND payment.pay_channel = 'ALIPAY_SANDBOX'
              AND (
                    payment.payment_state IN ('WAITING_PAYMENT', 'UNKNOWN')
                    OR (payment.payment_state = 'QUERYING' AND payment.update_time <= DATE_SUB(NOW(), INTERVAL 2 MINUTE))
                  )
              AND payment.update_time <= DATE_SUB(NOW(), INTERVAL 20 SECOND)
            ORDER BY payment.id
            LIMIT #{limit}
            """)
    List<OmsPaymentRecord> listQueryCandidates(@Param("limit") int limit);

    @Update("""
            UPDATE oms_payment_record SET payment_state = 'QUERYING', update_time = NOW()
            WHERE id = #{id}
              AND (
                    payment_state IN ('WAITING_PAYMENT', 'UNKNOWN')
                    OR (payment_state = 'QUERYING' AND update_time <= DATE_SUB(NOW(), INTERVAL 2 MINUTE))
                  )
            """)
    int claimQuery(@Param("id") Long id);

    @Update("""
            UPDATE oms_payment_record SET payment_state = 'WAITING_PAYMENT', update_time = NOW()
            WHERE id = #{id} AND payment_state = 'QUERYING'
            """)
    int markQueryWaiting(@Param("id") Long id);

    @Update("""
            UPDATE oms_payment_record SET payment_state = 'UNKNOWN', raw_callback = #{rawResponse}, update_time = NOW()
            WHERE id = #{id} AND payment_state = 'QUERYING'
            """)
    int markQueryUnknown(@Param("id") Long id, @Param("rawResponse") String rawResponse);

    @Update("""
            UPDATE oms_payment_record SET pay_status = 'CLOSED', payment_state = 'CLOSED',
                raw_callback = #{rawResponse}, update_time = NOW()
            WHERE id = #{id} AND payment_state = 'QUERYING'
            """)
    int markQueryClosed(@Param("id") Long id, @Param("rawResponse") String rawResponse);

    @Update("""
            UPDATE oms_payment_record SET payment_state = 'CLOSING', update_time = NOW()
            WHERE id = #{id}
              AND (
                    payment_state IN ('WAITING_PAYMENT', 'UNKNOWN', 'CLOSE_UNKNOWN')
                    OR (payment_state = 'CLOSING' AND update_time <= DATE_SUB(NOW(), INTERVAL 2 MINUTE))
                  )
            """)
    int claimClose(@Param("id") Long id);

    @Update("""
            UPDATE oms_payment_record SET payment_state = 'QUERYING', update_time = NOW()
            WHERE id = #{id} AND payment_state = 'CLOSING'
            """)
    int moveClosingToQuerying(@Param("id") Long id);

    @Update("""
            UPDATE oms_payment_record SET pay_status = 'CLOSED', payment_state = 'CLOSED',
                raw_callback = #{rawResponse}, update_time = NOW()
            WHERE id = #{id} AND payment_state = 'CLOSING'
            """)
    int markChannelClosed(@Param("id") Long id, @Param("rawResponse") String rawResponse);

    @Update("""
            UPDATE oms_payment_record SET payment_state = 'CLOSE_UNKNOWN',
                raw_callback = #{rawResponse}, update_time = NOW()
            WHERE id = #{id} AND payment_state = 'CLOSING'
            """)
    int markCloseUnknown(@Param("id") Long id, @Param("rawResponse") String rawResponse);

    @Update("""
            UPDATE oms_payment_record
            SET pay_status = 'REFUNDED', update_time = NOW()
            WHERE order_id = #{orderId}
              AND pay_status = 'PAID'
              AND amount = #{amount}
            """)
    int markRefunded(@Param("orderId") Long orderId, @Param("amount") java.math.BigDecimal amount);

    @Update("""
            UPDATE oms_payment_record
            SET pay_status = CASE WHEN refunded_amount + #{amount} = amount THEN 'REFUNDED' ELSE 'PARTIALLY_REFUNDED' END,
                refunded_amount = refunded_amount + #{amount},
                update_time = NOW()
            WHERE order_id = #{orderId}
              AND pay_status IN ('PAID', 'PARTIALLY_REFUNDED')
              AND refunded_amount + #{amount} <= amount
            """)
    int addRefund(@Param("orderId") Long orderId, @Param("amount") java.math.BigDecimal amount);
}
