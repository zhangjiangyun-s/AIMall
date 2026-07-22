package com.aimall.server.service.impl;

import com.aimall.server.entity.InventoryLedger;
import com.aimall.server.mapper.InventoryLedgerMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class InventoryAdjustmentLedgerServiceTest {
    @Test
    void adminAdjustmentUsesCanonicalDeltas() {
        InventoryLedgerMapper mapper = mock(InventoryLedgerMapper.class);
        InventoryAdjustmentLedgerService service = new InventoryAdjustmentLedgerService(mapper);

        service.record(10L, null, -3);

        ArgumentCaptor<InventoryLedger> captor = ArgumentCaptor.forClass(InventoryLedger.class);
        verify(mapper).insert(captor.capture());
        InventoryLedger event = captor.getValue();
        assertTrue(event.getEventId().startsWith("ADMIN-ADJUST-"));
        assertEquals(10L, event.getProductId());
        assertNull(event.getSkuId());
        assertEquals("ADJUST", event.getOperation());
        assertEquals(3, event.getQuantity());
        assertEquals(-3, event.getOnHandDelta());
        assertEquals(0, event.getReservedDelta());
        assertEquals(0, event.getSoldDelta());
        assertEquals(-3, event.getAvailableDelta());
    }
}
