package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_task_event")
public class KnowledgeTaskEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskId;
    private String eventType;
    private String title;
    private String detail;
    private Integer progressCurrent;
    private Integer progressTotal;
    private Boolean ok;
    private String errorCode;
    private String errorStack;
    private String suggestion;
    private LocalDateTime createdAt;
}
