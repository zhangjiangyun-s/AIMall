package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("pms_category_attribute_template")
public class PmsCategoryAttributeTemplate {
    @TableId(type = IdType.AUTO) private Long id;
    private Long categoryId;
    private String templateName;
    private String schemaJson;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
