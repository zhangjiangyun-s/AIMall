package com.aimall.server.mapper;

import com.aimall.server.entity.SmsCoupon;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SmsCouponMapper extends BaseMapper<SmsCoupon> {

    @Update("""
            UPDATE sms_coupon
            SET remaining_quantity = remaining_quantity - 1
            WHERE id = #{couponId}
              AND status = 1
              AND remaining_quantity > 0
              AND (receive_start_time IS NULL OR receive_start_time <= NOW())
              AND (receive_end_time IS NULL OR receive_end_time >= NOW())
            """)
    int reserveIssuance(@Param("couponId") Long couponId);

    @Update("""
            UPDATE sms_coupon
            SET used_budget = used_budget + #{amount}
            WHERE id = #{couponId}
              AND (budget_amount IS NULL OR used_budget + #{amount} <= budget_amount)
            """)
    int consumeBudget(@Param("couponId") Long couponId, @Param("amount") java.math.BigDecimal amount);

    @Update("""
            UPDATE sms_coupon
            SET used_budget = GREATEST(0, used_budget - #{amount})
            WHERE id = #{couponId}
            """)
    int releaseBudget(@Param("couponId") Long couponId, @Param("amount") java.math.BigDecimal amount);
}
