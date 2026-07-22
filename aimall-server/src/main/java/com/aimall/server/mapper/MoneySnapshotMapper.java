package com.aimall.server.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface MoneySnapshotMapper {
    @Insert("""
        INSERT INTO order_money_snapshot_v2(order_id,currency_code,currency_scale,total_amount,pay_amount,freight_amount,promotion_amount,coupon_amount,discount_amount,refunded_amount)
        VALUES(#{orderId},#{currency},#{scale},#{total},#{pay},#{freight},#{promotion},#{coupon},#{discount},#{refunded})
        ON DUPLICATE KEY UPDATE currency_code=VALUES(currency_code),currency_scale=VALUES(currency_scale),total_amount=VALUES(total_amount),pay_amount=VALUES(pay_amount),freight_amount=VALUES(freight_amount),promotion_amount=VALUES(promotion_amount),coupon_amount=VALUES(coupon_amount),discount_amount=VALUES(discount_amount),refunded_amount=VALUES(refunded_amount),updated_at=NOW(6)
        """)
    int upsertOrder(@Param("orderId") Long orderId,@Param("currency") String currency,@Param("scale") int scale,
                    @Param("total") BigDecimal total,@Param("pay") BigDecimal pay,@Param("freight") BigDecimal freight,
                    @Param("promotion") BigDecimal promotion,@Param("coupon") BigDecimal coupon,@Param("discount") BigDecimal discount,
                    @Param("refunded") BigDecimal refunded);

    @Insert("""
        INSERT INTO order_item_money_snapshot_v2(order_item_id,order_id,product_price,promotion_amount,coupon_amount,real_amount)
        VALUES(#{itemId},#{orderId},#{price},#{promotion},#{coupon},#{real})
        ON DUPLICATE KEY UPDATE product_price=VALUES(product_price),promotion_amount=VALUES(promotion_amount),coupon_amount=VALUES(coupon_amount),real_amount=VALUES(real_amount),updated_at=NOW(6)
        """)
    int upsertOrderItem(@Param("itemId") Long itemId,@Param("orderId") Long orderId,@Param("price") BigDecimal price,
                        @Param("promotion") BigDecimal promotion,@Param("coupon") BigDecimal coupon,@Param("real") BigDecimal real);

    @Insert("""
        INSERT INTO payment_money_snapshot_v2(payment_id,currency_code,currency_scale,amount,paid_amount,refunded_amount)
        VALUES(#{id},#{currency},#{scale},#{amount},#{paid},#{refunded})
        ON DUPLICATE KEY UPDATE currency_code=VALUES(currency_code),currency_scale=VALUES(currency_scale),amount=VALUES(amount),paid_amount=VALUES(paid_amount),refunded_amount=VALUES(refunded_amount),updated_at=NOW(6)
        """)
    int upsertPayment(@Param("id") Long id,@Param("currency") String currency,@Param("scale") int scale,
                      @Param("amount") BigDecimal amount,@Param("paid") BigDecimal paid,@Param("refunded") BigDecimal refunded);

    @Insert("""
        INSERT INTO refund_money_snapshot_v2(refund_record_id,currency_code,currency_scale,amount)
        VALUES(#{id},#{currency},#{scale},#{amount})
        ON DUPLICATE KEY UPDATE currency_code=VALUES(currency_code),currency_scale=VALUES(currency_scale),amount=VALUES(amount),updated_at=NOW(6)
        """)
    int upsertRefund(@Param("id") Long id,@Param("currency") String currency,@Param("scale") int scale,@Param("amount") BigDecimal amount);

    @Insert("""
        INSERT INTO return_item_money_snapshot_v2(return_item_id,order_item_id,refund_amount)
        VALUES(#{id},#{orderItemId},#{amount})
        ON DUPLICATE KEY UPDATE refund_amount=VALUES(refund_amount),updated_at=NOW(6)
        """)
    int upsertReturnItem(@Param("id") Long id,@Param("orderItemId") Long orderItemId,@Param("amount") BigDecimal amount);

    @Select("SELECT real_amount FROM order_item_money_snapshot_v2 WHERE order_item_id=#{id}")
    BigDecimal selectOrderItemRealAmount(@Param("id") Long id);

    @Select("SELECT COALESCE(SUM(refund_amount),0) FROM return_item_money_snapshot_v2 WHERE order_item_id=#{id}")
    BigDecimal selectCompletedRefundAmount(@Param("id") Long id);
}
