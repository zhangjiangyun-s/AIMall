package com.aimall.server.mapper;

import com.aimall.server.entity.OmsOrderItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OmsOrderItemMapper extends BaseMapper<OmsOrderItem> {

    @Select("""
            SELECT COALESCE(SUM(item.product_quantity), 0)
            FROM oms_order_item item
            JOIN oms_order order_row ON order_row.id = item.order_id
            WHERE order_row.member_id = #{memberId}
              AND order_row.status NOT IN (4, 5)
              AND item.product_id = #{productId}
              AND item.product_sku_id IS NULL
              AND order_row.create_time >= #{startTime}
              AND order_row.create_time <= #{endTime}
            """)
    int sumPurchasedWithoutSku(@Param("memberId") Long memberId,
                               @Param("productId") Long productId,
                               @Param("startTime") java.time.LocalDateTime startTime,
                               @Param("endTime") java.time.LocalDateTime endTime);

    @Select("""
            SELECT COALESCE(SUM(item.product_quantity), 0)
            FROM oms_order_item item
            JOIN oms_order order_row ON order_row.id = item.order_id
            WHERE order_row.member_id = #{memberId}
              AND order_row.status NOT IN (4, 5)
              AND item.product_id = #{productId}
              AND item.product_sku_id = #{skuId}
              AND order_row.create_time >= #{startTime}
              AND order_row.create_time <= #{endTime}
            """)
    int sumPurchasedWithSku(@Param("memberId") Long memberId,
                            @Param("productId") Long productId,
                            @Param("skuId") Long skuId,
                            @Param("startTime") java.time.LocalDateTime startTime,
                            @Param("endTime") java.time.LocalDateTime endTime);

    @Update("""
            UPDATE oms_order_item
            SET return_reserved_quantity = return_reserved_quantity + #{quantity}
            WHERE id = #{orderItemId}
              AND order_id = #{orderId}
              AND product_quantity - return_reserved_quantity - refunded_quantity >= #{quantity}
            """)
    int reserveReturnQuantity(@Param("orderItemId") Long orderItemId, @Param("orderId") Long orderId, @Param("quantity") int quantity);

    @Update("""
            UPDATE oms_order_item
            SET return_reserved_quantity = return_reserved_quantity - #{quantity}
            WHERE id = #{orderItemId}
              AND return_reserved_quantity >= #{quantity}
            """)
    int releaseReturnQuantity(@Param("orderItemId") Long orderItemId, @Param("quantity") int quantity);

    @Update("""
            UPDATE oms_order_item
            SET return_reserved_quantity = return_reserved_quantity - #{quantity},
                refunded_quantity = refunded_quantity + #{quantity}
            WHERE id = #{orderItemId}
              AND return_reserved_quantity >= #{quantity}
              AND refunded_quantity + #{quantity} <= product_quantity
            """)
    int finalizeReturnQuantity(@Param("orderItemId") Long orderItemId, @Param("quantity") int quantity);

    @Update("""
            UPDATE oms_order_item
            SET shipped_quantity = shipped_quantity + #{quantity}
            WHERE id = #{orderItemId}
              AND order_id = #{orderId}
              AND product_quantity - shipped_quantity - return_reserved_quantity - refunded_quantity >= #{quantity}
            """)
    int addShippedQuantity(@Param("orderItemId") Long orderItemId, @Param("orderId") Long orderId, @Param("quantity") int quantity);
}
