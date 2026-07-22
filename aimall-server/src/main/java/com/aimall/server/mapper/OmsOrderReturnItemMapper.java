package com.aimall.server.mapper;

import com.aimall.server.entity.OmsOrderReturnItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface OmsOrderReturnItemMapper extends BaseMapper<OmsOrderReturnItem> {

    @Select("""
            SELECT COALESCE(SUM(return_item.refund_amount), 0)
            FROM oms_order_return_item return_item
            JOIN oms_order_return_apply return_apply ON return_apply.id = return_item.return_apply_id
            WHERE return_item.order_item_id = #{orderItemId}
              AND return_apply.status = 3
            """)
    BigDecimal selectCompletedRefundAmount(@Param("orderItemId") Long orderItemId);
}
