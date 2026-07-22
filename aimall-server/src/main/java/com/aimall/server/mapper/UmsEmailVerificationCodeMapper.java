package com.aimall.server.mapper;

import com.aimall.server.entity.UmsEmailVerificationCode;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface UmsEmailVerificationCodeMapper extends BaseMapper<UmsEmailVerificationCode> {
    @Insert("""
            INSERT INTO ums_email_verification_code
                (email, purpose, code_hash, status, failed_attempts, max_attempts,
                 expires_at, last_sent_at, created_at, updated_at)
            VALUES
                (#{email}, #{purpose}, #{codeHash}, 'ACTIVE', 0, #{maxAttempts},
                 #{expiresAt}, #{now}, #{now}, #{now})
            ON DUPLICATE KEY UPDATE
                code_hash = IF(last_sent_at <= #{cooldownBefore}, VALUES(code_hash), code_hash),
                failed_attempts = IF(last_sent_at <= #{cooldownBefore}, 0, failed_attempts),
                max_attempts = IF(last_sent_at <= #{cooldownBefore}, VALUES(max_attempts), max_attempts),
                expires_at = IF(last_sent_at <= #{cooldownBefore}, VALUES(expires_at), expires_at),
                last_sent_at = IF(last_sent_at <= #{cooldownBefore}, VALUES(last_sent_at), last_sent_at),
                updated_at = IF(last_sent_at <= #{cooldownBefore}, VALUES(updated_at), updated_at)
            """)
    int upsertActive(
            @Param("email") String email,
            @Param("purpose") String purpose,
            @Param("codeHash") String codeHash,
            @Param("maxAttempts") int maxAttempts,
            @Param("expiresAt") LocalDateTime expiresAt,
            @Param("now") LocalDateTime now,
            @Param("cooldownBefore") LocalDateTime cooldownBefore
    );

    @Select("""
            SELECT * FROM ums_email_verification_code
            WHERE email = #{email} AND purpose = #{purpose} AND status = 'ACTIVE'
            LIMIT 1 FOR UPDATE
            """)
    UmsEmailVerificationCode selectActiveForUpdate(
            @Param("email") String email,
            @Param("purpose") String purpose
    );
}
