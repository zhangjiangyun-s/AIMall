package com.aimall.server.mapper;
import com.aimall.server.entity.UmsMemberProductFavorite; import com.baomidou.mybatisplus.core.mapper.BaseMapper; import org.apache.ibatis.annotations.*;
@Mapper public interface UmsMemberProductFavoriteMapper extends BaseMapper<UmsMemberProductFavorite>{
 @Insert("INSERT IGNORE INTO ums_member_product_favorite(member_id,product_id) VALUES(#{memberId},#{productId})") int insertIgnore(@Param("memberId")Long memberId,@Param("productId")Long productId);
}
