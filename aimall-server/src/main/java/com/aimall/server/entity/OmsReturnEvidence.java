package com.aimall.server.entity;
import com.baomidou.mybatisplus.annotation.*; import lombok.Data; import java.time.LocalDateTime;
@Data @TableName("oms_return_evidence") public class OmsReturnEvidence {
 @TableId(type=IdType.AUTO) private Long id; private Long returnApplyId; private Long memberId;
 private String mediaType; private String mediaUrl; private LocalDateTime createTime;
}
