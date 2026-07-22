package com.aimall.server.mapper;

import com.aimall.server.entity.OmsOrderRequest;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OmsOrderRequestMapper extends BaseMapper<OmsOrderRequest> {

    @Insert("""
            INSERT IGNORE INTO oms_order_request(member_id, request_id, status, create_time, update_time)
            VALUES(#{memberId}, #{requestId}, 'PROCESSING', NOW(), NOW())
            """)
    int reserve(@Param("memberId") Long memberId, @Param("requestId") String requestId);

    @Update("""
            UPDATE oms_order_request
            SET status = 'SUCCEEDED', order_id = #{orderId}, order_sn = #{orderSn}, update_time = NOW()
            WHERE member_id = #{memberId} AND request_id = #{requestId} AND status = 'PROCESSING'
            """)
    int markSucceeded(
            @Param("memberId") Long memberId,
            @Param("requestId") String requestId,
            @Param("orderId") Long orderId,
            @Param("orderSn") String orderSn
    );
}
