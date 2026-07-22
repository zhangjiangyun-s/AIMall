package com.aimall.server.mapper;

import com.aimall.server.entity.InventoryBalance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InventoryBalanceMapper {
    @Select("""
            SELECT inventory_type AS inventoryType, inventory_id AS inventoryId, product_id AS productId,
                   on_hand AS onHand, reserved, sold, available, enabled
            FROM inventory_balance_projection
            WHERE product_id = #{productId}
            ORDER BY inventory_type, inventory_id
            """)
    List<InventoryBalance> listByProductId(@Param("productId") Long productId);
}
