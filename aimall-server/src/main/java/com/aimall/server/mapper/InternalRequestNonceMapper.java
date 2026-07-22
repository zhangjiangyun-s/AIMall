package com.aimall.server.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface InternalRequestNonceMapper {

    @Insert("""
            INSERT IGNORE INTO internal_request_nonce(nonce, expires_at, create_time)
            VALUES(#{nonce}, #{expiresAt}, NOW())
            """)
    int reserve(@Param("nonce") String nonce, @Param("expiresAt") LocalDateTime expiresAt);

    @Delete("DELETE FROM internal_request_nonce WHERE expires_at < NOW() LIMIT 1000")
    int deleteExpired();
}
