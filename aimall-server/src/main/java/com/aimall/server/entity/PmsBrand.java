package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("pms_brand")
public class PmsBrand {
    @TableId(type = IdType.AUTO) private Long id;
    private String name;
    private String logo;
    private String description;
    private Integer sort;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
