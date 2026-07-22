package com.aimall.server.mapper; import com.aimall.server.entity.UmsMemberDevice; import com.baomidou.mybatisplus.core.mapper.BaseMapper; import org.apache.ibatis.annotations.*;
@Mapper public interface UmsMemberDeviceMapper extends BaseMapper<UmsMemberDevice>{@Insert("""
INSERT INTO ums_member_device(member_id,device_hash,device_name,last_ip,trusted,revoked,first_seen_time,last_seen_time)
VALUES(#{memberId},#{hash},#{name},#{ip},0,0,NOW(),NOW())
ON DUPLICATE KEY UPDATE device_name=#{name},last_ip=#{ip},last_seen_time=NOW()
""")int touch(@Param("memberId")Long memberId,@Param("hash")String hash,@Param("name")String name,@Param("ip")String ip);}
