package com.aimall.server.mapper;

import com.aimall.server.entity.OmsCartItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface OmsCartItemMapper extends BaseMapper<OmsCartItem> {

    @Insert("""
            INSERT IGNORE INTO oms_cart_item
                (product_id, product_sku_id, member_id, quantity, price, product_pic, product_name,
                 product_sub_title, product_sku_code, member_nickname, create_date, modify_date,
                 delete_status, product_category_name, product_brand, product_sn, product_attr)
            VALUES
                (#{productId}, #{productSkuId}, #{memberId}, #{quantity}, #{price}, #{productPic}, #{productName},
                 #{productSubTitle}, #{productSkuCode}, #{memberNickname}, #{createDate}, #{modifyDate},
                 #{deleteStatus}, #{productCategoryName}, #{productBrand}, #{productSn}, #{productAttr})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIgnore(OmsCartItem item);

    @Update("""
            UPDATE oms_cart_item
            SET quantity = quantity + #{delta}, price = #{currentPrice}, modify_date = NOW()
            WHERE id = #{id}
              AND member_id = #{memberId}
              AND delete_status = 0
              AND quantity + #{delta} <= #{maxQuantity}
            """)
    int incrementQuantity(
            @Param("id") Long id,
            @Param("memberId") Long memberId,
            @Param("delta") int delta,
            @Param("maxQuantity") int maxQuantity,
            @Param("currentPrice") BigDecimal currentPrice
    );
}
