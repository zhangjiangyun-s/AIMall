package com.aimall.server.entity;
import com.baomidou.mybatisplus.annotation.*; import lombok.Data; import java.time.LocalDateTime;
@Data @TableName("pms_product_review") public class PmsProductReview {
 @TableId(type=IdType.AUTO) private Long id; private Long memberId; private Long productId; private Long orderItemId;
 private Integer rating; private String content; private Integer status; private LocalDateTime createTime;
}
