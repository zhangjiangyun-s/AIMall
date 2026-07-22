package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsShipment;
import com.aimall.server.mapper.OmsOrderMapper;
import com.aimall.server.mapper.OmsShipmentMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogisticsAutoReceiveJobTest {

    @Test
    void delegatesFinalEligibilityToAtomicOrderUpdate() {
        OmsShipmentMapper shipmentMapper = mock(OmsShipmentMapper.class);
        OmsOrderMapper orderMapper = mock(OmsOrderMapper.class);
        OmsShipment shipment = new OmsShipment();
        shipment.setOrderId(100L);
        when(shipmentMapper.selectList(any())).thenReturn(List.of(shipment));

        new LogisticsAutoReceiveJob(shipmentMapper, orderMapper).autoReceiveDeliveredOrders();

        verify(orderMapper).autoConfirmReceived(eq(100L), any(LocalDateTime.class), any(LocalDateTime.class));
    }
}
