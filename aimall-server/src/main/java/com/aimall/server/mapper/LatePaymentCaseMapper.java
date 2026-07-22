package com.aimall.server.mapper;

import com.aimall.server.entity.LatePaymentCase;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LatePaymentCaseMapper extends BaseMapper<LatePaymentCase> {
    @Insert("""
            INSERT IGNORE INTO late_payment_case
              (payment_id, order_id, order_sn, amount, provider_trade_no, refund_request_id, status)
            VALUES (#{paymentId}, #{orderId}, #{orderSn}, #{amount}, #{providerTradeNo}, #{refundRequestId}, 'PENDING')
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int reserve(LatePaymentCase value);

    @Select("SELECT * FROM late_payment_case WHERE payment_id = #{paymentId} LIMIT 1")
    LatePaymentCase findByPaymentId(@Param("paymentId") Long paymentId);

    @Update("""
            UPDATE late_payment_case SET status = 'REFUNDING', retry_count = retry_count + 1, update_time = NOW(6)
            WHERE id = #{id} AND status IN ('PENDING', 'RETRY_WAIT')
            """)
    int claimRefund(@Param("id") Long id);

    @Update("""
            UPDATE late_payment_case SET status = 'QUERYING', query_retry_count = query_retry_count + 1, update_time = NOW(6)
            WHERE id = #{id} AND status = 'REFUND_UNKNOWN'
            """)
    int claimQuery(@Param("id") Long id);

    @Update("""
            UPDATE late_payment_case SET status = 'REFUND_UNKNOWN', last_error = #{error}, update_time = NOW(6)
            WHERE id = #{id} AND status IN ('REFUNDING', 'QUERYING')
            """)
    int markUnknown(@Param("id") Long id, @Param("error") String error);

    @Update("""
            UPDATE late_payment_case SET status = 'RETRY_WAIT', last_error = #{error}, update_time = NOW(6)
            WHERE id = #{id} AND status = 'QUERYING'
            """)
    int markNotFound(@Param("id") Long id, @Param("error") String error);

    @Update("""
            UPDATE late_payment_case SET status = 'REFUNDED', refund_trade_no = #{tradeNo},
                last_error = NULL, finished_time = NOW(6), update_time = NOW(6)
            WHERE id = #{id} AND status IN ('REFUNDING', 'QUERYING')
            """)
    int markRefunded(@Param("id") Long id, @Param("tradeNo") String tradeNo);
}
