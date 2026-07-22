package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ums_email_verification_code")
public class UmsEmailVerificationCode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String email;
    private String purpose;
    private String codeHash;
    private String status;
    private Integer failedAttempts;
    private Integer maxAttempts;
    private LocalDateTime expiresAt;
    private LocalDateTime lastSentAt;
    private LocalDateTime consumedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
