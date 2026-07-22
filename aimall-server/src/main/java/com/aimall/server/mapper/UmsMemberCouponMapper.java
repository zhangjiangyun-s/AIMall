package com.aimall.server.mapper;

import com.aimall.server.entity.UmsMemberCoupon;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UmsMemberCouponMapper extends BaseMapper<UmsMemberCoupon> {

    @Insert("""
            INSERT INTO ums_member_coupon
                (member_id, coupon_id, coupon_name, claim_no, status, usage_state, create_time)
            SELECT
                #{memberId}, #{couponId}, #{couponName},
                COALESCE(MAX(member_coupon.claim_no), 0) + 1, 0, 'AVAILABLE', #{createTime}
            FROM sms_coupon coupon
            LEFT JOIN ums_member_coupon member_coupon
              ON member_coupon.coupon_id = coupon.id
             AND member_coupon.member_id = #{memberId}
            WHERE coupon.id = #{couponId}
            GROUP BY coupon.id, coupon.per_member_limit
            HAVING COUNT(member_coupon.id) < coupon.per_member_limit
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertClaim(UmsMemberCoupon memberCoupon);

    @Update("""
            UPDATE ums_member_coupon
            SET status = 1,
                order_id = #{orderId},
                order_sn = #{orderSn},
                actual_discount_amount = #{actualDiscountAmount},
                used_time = NOW()
            WHERE id = #{memberCouponId}
              AND member_id = #{memberId}
              AND status = 0
            """)
    int markUsed(
            @Param("memberCouponId") Long memberCouponId,
            @Param("memberId") Long memberId,
            @Param("orderId") Long orderId,
            @Param("orderSn") String orderSn
            , @Param("actualDiscountAmount") java.math.BigDecimal actualDiscountAmount
    );

    @Update("""
            UPDATE ums_member_coupon
            SET usage_state='LOCKED', locked_order_id=#{orderId}, locked_at=NOW(6),
                order_id=#{orderId}, order_sn=#{orderSn}
            WHERE id=#{memberCouponId} AND member_id=#{memberId} AND status=0
              AND usage_state IN ('AVAILABLE','RELEASED','RESTORED')
              AND (locked_order_id IS NULL OR locked_order_id=#{orderId})
            """)
    int lockForOrder(@Param("memberCouponId") Long memberCouponId, @Param("memberId") Long memberId,
                     @Param("orderId") Long orderId, @Param("orderSn") String orderSn);

    @Update("""
            UPDATE ums_member_coupon
            SET status=1, usage_state='CONSUMED', actual_discount_amount=#{actualDiscountAmount},
                used_time=NOW(6), consumed_at=NOW(6)
            WHERE id=#{memberCouponId} AND member_id=#{memberId} AND status=0
              AND usage_state='LOCKED' AND locked_order_id=#{orderId}
            """)
    int consumeLocked(@Param("memberCouponId") Long memberCouponId, @Param("memberId") Long memberId,
                      @Param("orderId") Long orderId,
                      @Param("actualDiscountAmount") java.math.BigDecimal actualDiscountAmount);

    @Update("""
            UPDATE ums_member_coupon
            SET status=0, usage_state='RELEASED', order_id=NULL, order_sn=NULL,
                locked_order_id=NULL, used_time=NULL, actual_discount_amount=NULL, released_at=NOW(6)
            WHERE id=#{memberCouponId} AND order_id=#{orderId} AND status=1 AND usage_state='CONSUMED'
            """)
    int releaseConsumed(@Param("memberCouponId") Long memberCouponId, @Param("orderId") Long orderId);

    @Update("""
            UPDATE ums_member_coupon
            SET status=0, usage_state='RESTORED', order_id=NULL, order_sn=NULL,
                locked_order_id=NULL, used_time=NULL, actual_discount_amount=NULL, released_at=NOW(6)
            WHERE id=#{memberCouponId} AND order_id=#{orderId} AND status=1 AND usage_state='CONSUMED'
            """)
    int restoreConsumed(@Param("memberCouponId") Long memberCouponId, @Param("orderId") Long orderId);

    @Update("""
            UPDATE ums_member_coupon
            SET status=2, usage_state='VOID', released_at=NOW(6)
            WHERE id=#{memberCouponId} AND status=1 AND usage_state='CONSUMED'
            """)
    int voidConsumed(@Param("memberCouponId") Long memberCouponId);

    @Update("""
            UPDATE ums_member_coupon member_coupon
            JOIN sms_coupon coupon ON coupon.id=member_coupon.coupon_id
            SET member_coupon.status=2, member_coupon.usage_state='EXPIRED', member_coupon.released_at=NOW(6)
            WHERE member_coupon.status=0
              AND member_coupon.usage_state IN ('AVAILABLE','RELEASED','RESTORED')
              AND coupon.end_time IS NOT NULL AND coupon.end_time < NOW(6)
            """)
    int expireAvailable();

    @Update("""
            UPDATE ums_member_coupon
            SET status = 0, order_id = NULL, order_sn = NULL, used_time = NULL, actual_discount_amount = NULL
            WHERE member_id = #{memberId}
              AND order_id = #{orderId}
              AND status = 1
            """)
    int releaseUsed(@Param("memberId") Long memberId, @Param("orderId") Long orderId);

    @Update("""
            UPDATE ums_member_coupon
            SET status = 2
            WHERE id = #{memberCouponId} AND status = 1
            """)
    int voidUsed(@Param("memberCouponId") Long memberCouponId);
}
