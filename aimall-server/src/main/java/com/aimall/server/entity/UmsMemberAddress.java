package com.aimall.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ums_member_address")
public class UmsMemberAddress {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long memberId;
    private String name;
    private String phone;
    private String province;
    private String city;
    private String region;
    private String detailAddress;
    private Integer defaultStatus;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
