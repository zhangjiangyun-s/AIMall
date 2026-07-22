package com.aimall.server.mapper;

import com.aimall.server.entity.OmsOrderReturnApply;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface OmsOrderReturnApplyMapper extends BaseMapper<OmsOrderReturnApply> {
    @Update("""
            UPDATE oms_order_return_apply SET status=7, return_carrier=#{carrier}, return_tracking_no=#{trackingNo}, update_time=NOW()
            WHERE id=#{id} AND member_id=#{memberId} AND status=1 AND type='RETURN_REFUND'
            """)
    int submitReturnLogistics(@Param("id")Long id,@Param("memberId")Long memberId,@Param("carrier")String carrier,@Param("trackingNo")String trackingNo);

    @Update("""
            UPDATE oms_order_return_apply SET status=#{targetStatus}, inspection_result=#{result}, inspection_note=#{note},
                   received_time=NOW(), update_time=NOW()
            WHERE id=#{id} AND status=7
            """)
    int inspectReceived(@Param("id")Long id,@Param("targetStatus")int targetStatus,@Param("result")String result,@Param("note")String note);

    @Update("""
            UPDATE oms_order_return_apply SET sla_overdue=1, update_time=NOW()
            WHERE status IN (0,1,7,8,5) AND sla_overdue=0 AND sla_deadline < NOW()
            """)
    int markSlaOverdue();

    @Insert("""
            INSERT IGNORE INTO oms_order_return_apply
                (order_id, order_sn, member_id, status, type, reason, description, return_amount, create_time, update_time)
            VALUES
                (#{orderId}, #{orderSn}, #{memberId}, #{status}, #{type}, #{reason}, #{description}, #{returnAmount}, #{createTime}, #{updateTime})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIgnore(OmsOrderReturnApply apply);

    @Update("""
            UPDATE oms_order_return_apply
            SET status = #{targetStatus}, handle_note = #{handleNote}, handle_time = NOW(), update_time = NOW()
            WHERE id = #{id} AND status = #{expectedStatus}
            """)
    int transition(
            @Param("id") Long id,
            @Param("expectedStatus") int expectedStatus,
            @Param("targetStatus") int targetStatus,
            @Param("handleNote") String handleNote
    );
}
