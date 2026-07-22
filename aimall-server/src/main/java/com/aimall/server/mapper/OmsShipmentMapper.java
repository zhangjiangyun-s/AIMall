package com.aimall.server.mapper;

import com.aimall.server.entity.OmsShipment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OmsShipmentMapper extends BaseMapper<OmsShipment> {

    @Update("""
            UPDATE oms_shipment
            SET status = #{status},
                delivered_at = CASE WHEN #{status} = 'DELIVERED' THEN #{eventTime} ELSE delivered_at END,
                update_time = NOW()
            WHERE id = #{shipmentId}
              AND status NOT IN ('DELIVERED', 'CANCELED')
            """)
    int updateStatus(
            @Param("shipmentId") Long shipmentId,
            @Param("status") String status,
            @Param("eventTime") java.time.LocalDateTime eventTime
    );
}
