package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("admin_operation_audit")
public class AdminOperationAudit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long operatorId;
    private String operatorName;
    private String httpMethod;
    private String requestUri;
    private String clientIp;
    private String traceId;
    private Integer success;
    private String errorMessage;
    private Long durationMs;
    private LocalDateTime createTime;
}
