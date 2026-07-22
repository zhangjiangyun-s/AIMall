package com.aimall.server.mapper;

import com.aimall.server.entity.PmsProduct;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PmsProductMapper extends BaseMapper<PmsProduct> {

    @Update("""
            UPDATE pms_product product
            SET publish_status = #{targetStatus}
            WHERE product.id = #{productId}
              AND product.delete_status = 0
              AND product.publish_status = #{expectedStatus}
              AND (
                    #{targetStatus} = 0
                    OR (
                        product.verify_status = 1
                        AND product.category_id IS NOT NULL
                        AND product.name IS NOT NULL AND product.name <> ''
                        AND COALESCE(product.price_v2, product.price) IS NOT NULL
                        AND COALESCE(product.price_v2, product.price) >= 0
                        AND (
                            NOT EXISTS (SELECT 1 FROM pms_sku_stock sku WHERE sku.product_id = product.id)
                            OR EXISTS (SELECT 1 FROM pms_sku_stock sku WHERE sku.product_id = product.id AND sku.status = 1)
                        )
                    )
                  )
            """)
    int transitionPublishStatus(@Param("productId") Long productId,
                                @Param("expectedStatus") int expectedStatus,
                                @Param("targetStatus") int targetStatus);

    @Update("""
            UPDATE pms_product
            SET lock_stock = lock_stock + #{quantity}
            WHERE id = #{productId}
              AND delete_status = 0
              AND publish_status = 1
              AND stock - lock_stock >= #{quantity}
            """)
    int reserveStock(@Param("productId") Long productId, @Param("quantity") int quantity);

    @Update("""
            UPDATE pms_product
            SET lock_stock = lock_stock - #{quantity}
            WHERE id = #{productId}
              AND lock_stock >= #{quantity}
            """)
    int releaseStock(@Param("productId") Long productId, @Param("quantity") int quantity);

    @Update("""
            UPDATE pms_product
            SET stock = stock - #{quantity},
                lock_stock = lock_stock - #{quantity},
                sale = sale + #{quantity}
            WHERE id = #{productId}
              AND stock >= #{quantity}
              AND lock_stock >= #{quantity}
            """)
    int deductReservedStock(@Param("productId") Long productId, @Param("quantity") int quantity);

    @Update("""
            UPDATE pms_product
            SET stock = stock + #{quantity}, sale = GREATEST(0, sale - #{quantity})
            WHERE id = #{productId} AND delete_status = 0
            """)
    int restoreStock(@Param("productId") Long productId, @Param("quantity") int quantity);

    @Update("""
            UPDATE pms_product product
            SET product.stock = product.stock + #{delta}
            WHERE product.id = #{productId}
              AND product.delete_status = 0
              AND product.stock + #{delta} >= 0
              AND product.stock + #{delta} >= product.lock_stock
              AND NOT EXISTS (
                    SELECT 1 FROM pms_sku_stock sku
                    WHERE sku.product_id = product.id
                  )
            """)
    int adjustStandaloneStock(@Param("productId") Long productId, @Param("delta") int delta);

    @Update("""
            UPDATE pms_product product
            SET product.delete_status = 1,
                product.publish_status = 0
            WHERE product.id = #{productId}
              AND product.delete_status = 0
              AND product.lock_stock = 0
              AND NOT EXISTS (
                    SELECT 1 FROM pms_sku_stock sku
                    WHERE sku.product_id = product.id
                      AND sku.lock_stock > 0
                  )
            """)
    int softDeleteForAdmin(@Param("productId") Long productId);
}
