package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ums_email_verification_send_log")
public class UmsEmailVerificationSendLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String email;
    private String purpose;
    private String clientIp;
    private Integer success;
    private String failureReason;
    private LocalDateTime createdAt;
}
