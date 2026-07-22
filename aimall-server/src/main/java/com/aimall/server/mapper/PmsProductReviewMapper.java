package com.aimall.server.mapper;
import com.aimall.server.entity.PmsProductReview; import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
@Mapper public interface PmsProductReviewMapper extends BaseMapper<PmsProductReview> {
 @Select("""
 SELECT COUNT(*) FROM oms_order_item i JOIN oms_order o ON o.id=i.order_id
 WHERE i.id=#{orderItemId} AND i.product_id=#{productId} AND o.member_id=#{memberId} AND o.status=3
 """)
 int canReview(@Param("memberId")Long memberId,@Param("productId")Long productId,@Param("orderItemId")Long orderItemId);
}
