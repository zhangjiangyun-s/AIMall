package com.aimall.server.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AdminLoginAttemptMapper {

    @Select("""
            SELECT COUNT(*) FROM ums_admin_login_attempt
            WHERE username = #{username}
              AND client_ip = #{clientIp}
              AND success = 0
              AND create_time >= DATE_SUB(NOW(), INTERVAL 15 MINUTE)
            """)
    int recentFailures(@Param("username") String username, @Param("clientIp") String clientIp);

    @Insert("""
            INSERT INTO ums_admin_login_attempt (username, client_ip, success, create_time)
            VALUES (#{username}, #{clientIp}, 0, NOW())
            """)
    int recordFailure(@Param("username") String username, @Param("clientIp") String clientIp);

    @Delete("DELETE FROM ums_admin_login_attempt WHERE username = #{username} AND client_ip = #{clientIp}")
    int clear(@Param("username") String username, @Param("clientIp") String clientIp);
}
