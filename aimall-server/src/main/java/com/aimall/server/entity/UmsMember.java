package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ums_member")
public class UmsMember {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String password;
    private String nickname;
    private String phone;
    private String email;
    private String memberLevel;
    private Integer status;
    private String privacyConsentVersion;
    private LocalDateTime privacyConsentTime;
    private LocalDateTime passwordChangedAt;
    private LocalDateTime cancelledAt;
    private LocalDateTime createTime;
}
