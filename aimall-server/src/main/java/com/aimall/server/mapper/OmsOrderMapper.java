package com.aimall.server.mapper;

import com.aimall.server.entity.OmsOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface OmsOrderMapper extends BaseMapper<OmsOrder> {

    @Update("""
            UPDATE oms_order
            SET expire_time = #{requestTime}, modify_time = #{requestTime}
            WHERE id = #{orderId}
              AND member_id = #{memberId}
              AND delete_status = 0
              AND status = 0
            """)
    int requestCancellation(@Param("orderId") Long orderId, @Param("memberId") Long memberId,
                            @Param("requestTime") LocalDateTime requestTime);

    @Update("""
            UPDATE oms_order
            SET refund_status = 'FULL_REFUNDED', refunded_amount = pay_amount, modify_time = NOW(6)
            WHERE id = #{orderId} AND status = 4
              AND refund_status IN ('NONE', 'PARTIALLY_REFUNDED')
              AND refunded_amount < pay_amount
            """)
    int markLatePaymentRefunded(@Param("orderId") Long orderId);

    @Update("""
            UPDATE oms_order order_row
            SET status = #{targetStatus},
                inventory_reservation_status = #{targetInventoryStatus},
                modify_time = #{modifyTime}
            WHERE id = #{orderId}
              AND member_id = #{memberId}
              AND delete_status = 0
              AND status = #{expectedStatus}
              AND inventory_reservation_status = #{expectedInventoryStatus}
            """)
    int transitionOwnedStatusAndInventory(
            @Param("orderId") Long orderId,
            @Param("memberId") Long memberId,
            @Param("expectedStatus") int expectedStatus,
            @Param("targetStatus") int targetStatus,
            @Param("expectedInventoryStatus") String expectedInventoryStatus,
            @Param("targetInventoryStatus") String targetInventoryStatus,
            @Param("modifyTime") LocalDateTime modifyTime
    );

    @Update("""
            UPDATE oms_order
            SET status = 1,
                pay_type = #{payType},
                payment_time = #{paymentTime},
                inventory_reservation_status = 'DEDUCTED',
                modify_time = #{paymentTime}
            WHERE id = #{orderId}
              AND member_id = #{memberId}
              AND delete_status = 0
              AND status = 0
              AND inventory_reservation_status = 'RESERVED'
            """)
    int markPaid(
            @Param("orderId") Long orderId,
            @Param("memberId") Long memberId,
            @Param("payType") int payType,
            @Param("paymentTime") LocalDateTime paymentTime
    );

    @Update("""
            UPDATE oms_order
            SET status = 2,
                delivery_company = #{deliveryCompany},
                delivery_sn = #{deliverySn},
                delivery_time = #{deliveryTime},
                modify_time = #{deliveryTime}
            WHERE order_row.id = #{orderId}
              AND order_row.delete_status = 0
              AND order_row.status = 1
              AND order_row.financial_hold = 0
              AND order_row.refund_status = 'NONE'
              AND EXISTS (
                    SELECT 1 FROM oms_payment_record payment
                    WHERE payment.order_id = order_row.id
                      AND payment.pay_status = 'PAID'
                      AND payment.payment_state = 'PAID'
                      AND payment.paid_amount = order_row.pay_amount
                      AND payment.refunded_amount = 0
                  )
            """)
    int ship(
            @Param("orderId") Long orderId,
            @Param("deliveryCompany") String deliveryCompany,
            @Param("deliverySn") String deliverySn,
            @Param("deliveryTime") LocalDateTime deliveryTime
    );

    @Update("""
            UPDATE oms_order
            SET financial_hold=1, financial_hold_reason=#{reason}, modify_time=NOW(6)
            WHERE id=#{orderId} AND financial_hold=0
            """)
    int placeFinancialHold(@Param("orderId") Long orderId, @Param("reason") String reason);

    @Update("""
            UPDATE oms_order
            SET financial_hold=0, financial_hold_reason=NULL, modify_time=NOW(6)
            WHERE id=#{orderId} AND financial_hold=1
            """)
    int releaseFinancialHold(@Param("orderId") Long orderId);

    @Update("""
            UPDATE oms_order
            SET status = 3, confirm_status = 1, receive_time = #{receiveTime}, modify_time = #{receiveTime}
            WHERE id = #{orderId}
              AND member_id = #{memberId}
              AND delete_status = 0
              AND status = 2
            """)
    int confirmReceived(
            @Param("orderId") Long orderId,
            @Param("memberId") Long memberId,
            @Param("receiveTime") LocalDateTime receiveTime
    );

    @Update("""
            UPDATE oms_order
            SET refund_status = 'FULL_REFUNDED', refunded_amount = #{amount}, modify_time = #{refundTime}
            WHERE id = #{orderId}
              AND delete_status = 0
              AND refund_status = 'NONE'
              AND pay_amount = #{amount}
            """)
    int markFullRefunded(
            @Param("orderId") Long orderId,
            @Param("amount") java.math.BigDecimal amount,
            @Param("refundTime") LocalDateTime refundTime
    );

    @Update("""
            UPDATE oms_order
            SET refund_status = CASE WHEN refunded_amount + #{amount} = pay_amount THEN 'FULL_REFUNDED' ELSE 'PARTIALLY_REFUNDED' END,
                refunded_amount = refunded_amount + #{amount},
                modify_time = #{refundTime}
            WHERE id = #{orderId}
              AND delete_status = 0
              AND refunded_amount + #{amount} <= pay_amount
            """)
    int addRefund(
            @Param("orderId") Long orderId,
            @Param("amount") java.math.BigDecimal amount,
            @Param("refundTime") LocalDateTime refundTime
    );

    @Update("""
            UPDATE oms_order
            SET status = 4,
                inventory_reservation_status = #{targetInventoryStatus},
                modify_time = #{closeTime}
            WHERE id = #{orderId}
              AND delete_status = 0
              AND status = 0
              AND inventory_reservation_status = #{expectedInventoryStatus}
              AND expire_time IS NOT NULL
              AND expire_time <= #{closeTime}
            """)
    int closeExpired(
            @Param("orderId") Long orderId,
            @Param("expectedInventoryStatus") String expectedInventoryStatus,
            @Param("targetInventoryStatus") String targetInventoryStatus,
            @Param("closeTime") LocalDateTime closeTime
    );

    @Update("""
            UPDATE oms_order
            SET inventory_reservation_status = 'DEDUCTED', modify_time = #{modifyTime}
            WHERE id = #{orderId}
              AND inventory_reservation_status = 'RESERVED'
            """)
    int markInventoryDeducted(@Param("orderId") Long orderId, @Param("modifyTime") LocalDateTime modifyTime);

    @Update("""
            UPDATE oms_order order_row
            SET status = 3, confirm_status = 1, receive_time = #{receiveTime}, modify_time = #{receiveTime}
            WHERE order_row.id = #{orderId}
              AND order_row.delete_status = 0
              AND order_row.status = 2
              AND EXISTS (
                    SELECT 1 FROM oms_shipment shipment
                    WHERE shipment.order_id = order_row.id
                  )
              AND NOT EXISTS (
                    SELECT 1 FROM oms_shipment shipment
                    WHERE shipment.order_id = order_row.id
                      AND (shipment.status <> 'DELIVERED' OR shipment.delivered_at IS NULL OR shipment.delivered_at > #{cutoff})
                  )
              AND NOT EXISTS (
                    SELECT 1 FROM oms_order_item item
                    WHERE item.order_id = order_row.id
                      AND (
                            item.return_reserved_quantity > 0
                            OR item.shipped_quantity < item.product_quantity - item.refunded_quantity
                          )
                  )
            """)
    int autoConfirmReceived(
            @Param("orderId") Long orderId,
            @Param("cutoff") LocalDateTime cutoff,
            @Param("receiveTime") LocalDateTime receiveTime
    );
}
