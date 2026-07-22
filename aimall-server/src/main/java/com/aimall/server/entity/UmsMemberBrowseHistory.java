package com.aimall.server.entity;
import com.baomidou.mybatisplus.annotation.*; import lombok.Data; import java.time.LocalDateTime;
@Data @TableName("ums_member_browse_history") public class UmsMemberBrowseHistory {
 @TableId(type=IdType.AUTO) private Long id; private Long memberId; private Long productId; private Integer viewCount; private LocalDateTime lastViewTime;
}
