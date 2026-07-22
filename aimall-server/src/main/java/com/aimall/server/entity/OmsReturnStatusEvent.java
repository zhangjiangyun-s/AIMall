package com.aimall.server.entity;
import com.baomidou.mybatisplus.annotation.*; import lombok.Data; import java.time.LocalDateTime;
@Data @TableName("oms_return_status_event") public class OmsReturnStatusEvent {
 @TableId(type=IdType.AUTO) private Long id; private Long returnApplyId; private Integer fromStatus; private Integer toStatus;
 private String eventType; private Long operatorId; private String operatorType; private String note; private LocalDateTime createTime;
}
