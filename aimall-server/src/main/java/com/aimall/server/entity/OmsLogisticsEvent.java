package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oms_logistics_event")
public class OmsLogisticsEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shipmentId;
    private String eventCode;
    private LocalDateTime eventTime;
    private String location;
    private String description;
    private String source;
    private LocalDateTime createTime;
}
