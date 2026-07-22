package com.aimall.server.entity;
import com.baomidou.mybatisplus.annotation.*; import lombok.Data; import java.time.LocalDateTime;
@Data @TableName("ums_member_product_favorite") public class UmsMemberProductFavorite {
 @TableId(type=IdType.AUTO) private Long id; private Long memberId; private Long productId; private LocalDateTime createTime;
}
