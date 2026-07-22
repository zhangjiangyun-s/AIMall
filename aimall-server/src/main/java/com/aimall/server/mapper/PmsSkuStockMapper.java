package com.aimall.server.mapper;

import com.aimall.server.entity.PmsSkuStock;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;
import java.math.BigDecimal;
import java.util.List;
import com.aimall.server.entity.ProductStockAlert;

@Mapper
public interface PmsSkuStockMapper extends BaseMapper<PmsSkuStock> {

    @Select("""
            SELECT product.id AS productId, sku.id AS skuId, product.name AS productName,
                   sku.sku_code AS skuCode, GREATEST(sku.stock - sku.lock_stock, 0) AS availableStock,
                   sku.low_stock AS lowStock, product.publish_status AS publishStatus
            FROM pms_sku_stock sku
            JOIN pms_product product ON product.id = sku.product_id
            WHERE product.delete_status = 0 AND sku.status = 1
              AND GREATEST(sku.stock - sku.lock_stock, 0) <= sku.low_stock
            UNION ALL
            SELECT product.id AS productId, NULL AS skuId, product.name AS productName,
                   NULL AS skuCode, GREATEST(product.stock - product.lock_stock, 0) AS availableStock,
                   product.low_stock AS lowStock, product.publish_status AS publishStatus
            FROM pms_product product
            WHERE product.delete_status = 0
              AND NOT EXISTS (SELECT 1 FROM pms_sku_stock sku WHERE sku.product_id = product.id)
              AND GREATEST(product.stock - product.lock_stock, 0) <= product.low_stock
            ORDER BY availableStock ASC, productId ASC
            LIMIT #{limit}
            """)
    List<ProductStockAlert> listLowStockAlerts(@Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM pms_sku_stock WHERE product_id = #{productId}")
    long countAllByProductId(@Param("productId") Long productId);

    @Select("""
            SELECT COALESCE(SUM(GREATEST(stock - lock_stock, 0)), 0)
            FROM pms_sku_stock
            WHERE product_id = #{productId} AND status = 1
            """)
    int sumEnabledAvailableStock(@Param("productId") Long productId);

    @Select("""
            <script>
            SELECT product_id AS productId,
                   COUNT(*) AS skuCount,
                   COALESCE(SUM(CASE WHEN status = 1 THEN GREATEST(stock - lock_stock, 0) ELSE 0 END), 0) AS availableStock
            FROM pms_sku_stock
            WHERE product_id IN
            <foreach collection="productIds" item="productId" open="(" separator="," close=")">
                #{productId}
            </foreach>
            GROUP BY product_id
            </script>
            """)
    List<ProductStockAggregate> aggregateAvailableStock(@Param("productIds") List<Long> productIds);

    @Update("""
            UPDATE pms_sku_stock
            SET lock_stock = lock_stock + #{quantity}
            WHERE id = #{skuId}
              AND product_id = #{productId}
              AND stock - lock_stock >= #{quantity}
              AND status = 1
              AND EXISTS (
                    SELECT 1 FROM pms_product product
                    WHERE product.id = pms_sku_stock.product_id
                      AND product.delete_status = 0
                      AND product.publish_status = 1
                  )
            """)
    int reserveStock(@Param("skuId") Long skuId, @Param("productId") Long productId, @Param("quantity") int quantity);

    @Update("""
            UPDATE pms_sku_stock
            SET lock_stock = lock_stock - #{quantity}
            WHERE id = #{skuId}
              AND product_id = #{productId}
              AND lock_stock >= #{quantity}
            """)
    int releaseStock(@Param("skuId") Long skuId, @Param("productId") Long productId, @Param("quantity") int quantity);

    @Update("""
            UPDATE pms_sku_stock
            SET stock = stock - #{quantity},
                lock_stock = lock_stock - #{quantity},
                sale = sale + #{quantity}
            WHERE id = #{skuId}
              AND product_id = #{productId}
              AND stock >= #{quantity}
              AND lock_stock >= #{quantity}
            """)
    int deductReservedStock(@Param("skuId") Long skuId, @Param("productId") Long productId, @Param("quantity") int quantity);

    @Update("""
            UPDATE pms_sku_stock
            SET stock = stock + #{quantity}, sale = GREATEST(0, sale - #{quantity})
            WHERE id = #{skuId} AND product_id = #{productId}
            """)
    int restoreStock(@Param("skuId") Long skuId, @Param("productId") Long productId, @Param("quantity") int quantity);

    @Update("""
            <script>
            UPDATE pms_sku_stock
            <set>
                <if test="update.updateSkuCode">sku_code = #{update.skuCode},</if>
                <if test="update.updatePrice">price_v2 = #{update.price}, price = ROUND(#{update.price}, 2),</if>
                <if test="update.updatePromotionPrice">promotion_price_v2 = #{update.promotionPrice}, promotion_price = ROUND(#{update.promotionPrice}, 2),</if>
                <if test="update.updateLowStock">low_stock = #{update.lowStock},</if>
                <if test="update.updatePic">pic = #{update.pic},</if>
                <if test="update.updateSpData">sp_data = #{update.spData},</if>
                <if test="update.updateStatus">status = #{update.status},</if>
            </set>
            WHERE id = #{update.skuId}
              AND product_id = #{update.productId}
              AND (#{update.updateStatus} = FALSE OR #{update.status} &lt;&gt; 0 OR lock_stock = 0)
              AND (#{update.updateLowStock} = FALSE OR #{update.lowStock} &lt;= stock)
            </script>
            """)
    int updateAdminFields(@Param("update") AdminUpdate update);

    record AdminUpdate(
            Long skuId,
            Long productId,
            String skuCode,
            BigDecimal price,
            BigDecimal promotionPrice,
            Integer lowStock,
            String pic,
            String spData,
            Integer status,
            boolean updateSkuCode,
            boolean updatePrice,
            boolean updatePromotionPrice,
            boolean updateLowStock,
            boolean updatePic,
            boolean updateSpData,
            boolean updateStatus
    ) {
        public boolean hasChanges() {
            return updateSkuCode || updatePrice || updatePromotionPrice || updateLowStock
                    || updatePic || updateSpData || updateStatus;
        }
    }

    @Update("""
            UPDATE pms_sku_stock
            SET stock = stock + #{delta}
            WHERE id = #{skuId}
              AND product_id = #{productId}
              AND stock + #{delta} >= 0
              AND stock + #{delta} >= lock_stock
            """)
    int adjustStock(
            @Param("skuId") Long skuId,
            @Param("productId") Long productId,
            @Param("delta") int delta
    );

    @Update("""
            UPDATE pms_sku_stock
            SET status = 0
            WHERE id = #{skuId}
              AND product_id = #{productId}
              AND lock_stock = 0
            """)
    int disableForAdmin(@Param("skuId") Long skuId, @Param("productId") Long productId);
}
