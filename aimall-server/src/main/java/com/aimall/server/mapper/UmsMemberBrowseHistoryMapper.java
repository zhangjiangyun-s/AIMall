package com.aimall.server.mapper;
import com.aimall.server.entity.UmsMemberBrowseHistory; import com.baomidou.mybatisplus.core.mapper.BaseMapper; import org.apache.ibatis.annotations.*;
@Mapper public interface UmsMemberBrowseHistoryMapper extends BaseMapper<UmsMemberBrowseHistory>{
 @Insert("""
 INSERT INTO ums_member_browse_history(member_id,product_id,view_count,last_view_time)
 VALUES(#{memberId},#{productId},1,NOW())
 ON DUPLICATE KEY UPDATE view_count=view_count+1,last_view_time=NOW()
 """) int record(@Param("memberId")Long memberId,@Param("productId")Long productId);
}
