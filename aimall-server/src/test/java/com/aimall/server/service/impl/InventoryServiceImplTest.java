package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsCartItem;
import com.aimall.server.entity.OmsOrderItem;
import com.aimall.server.mapper.PmsProductMapper;
import com.aimall.server.mapper.PmsSkuStockMapper;
import com.aimall.server.mapper.InventoryLedgerMapper;
import com.aimall.server.entity.InventoryLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;

class InventoryServiceImplTest {

    private PmsSkuStockMapper skuStockMapper;
    private PmsProductMapper productMapper;
    private InventoryLedgerMapper ledgerMapper;
    private InventoryServiceImpl inventoryService;

    @BeforeEach
    void setUp() {
        skuStockMapper = mock(PmsSkuStockMapper.class);
        productMapper = mock(PmsProductMapper.class);
        ledgerMapper = mock(InventoryLedgerMapper.class);
        inventoryService = new InventoryServiceImpl(skuStockMapper, productMapper, ledgerMapper);
    }

    @Test
    void reserveSkuUsesSkuAsTheOnlyInventorySource() {
        OmsCartItem item = cartItem(1001L, 2001L, 2);
        when(skuStockMapper.reserveStock(2001L, 1001L, 2)).thenReturn(1);

        inventoryService.reserveForCartItems(List.of(item));

        verify(skuStockMapper).reserveStock(2001L, 1001L, 2);
        org.mockito.Mockito.verifyNoInteractions(productMapper);
    }

    @Test
    void reserveWithoutSkuStillRecordsProductLockStock() {
        OmsCartItem item = cartItem(1001L, null, 1);
        when(productMapper.reserveStock(1001L, 1)).thenReturn(1);

        inventoryService.reserveForCartItems(List.of(item));

        verify(productMapper).reserveStock(1001L, 1);
    }

    @Test
    void reserveRejectsWhenConditionalUpdateAffectsNoRows() {
        OmsCartItem item = cartItem(1001L, 2001L, 3);
        when(skuStockMapper.reserveStock(2001L, 1001L, 3)).thenReturn(0);

        assertThrows(
                RuntimeException.class,
                () -> inventoryService.reserveForCartItems(List.of(item))
        );
    }

    @Test
    void reserveUsesCanonicalProductSkuItemLockOrder() {
        OmsCartItem last = cartItem(1002L, 2002L, 1);
        last.setId(3003L);
        OmsCartItem first = cartItem(1001L, 2002L, 1);
        first.setId(3002L);
        OmsCartItem second = cartItem(1001L, 2001L, 1);
        second.setId(3001L);
        when(skuStockMapper.reserveStock(2001L, 1001L, 1)).thenReturn(1);
        when(skuStockMapper.reserveStock(2002L, 1001L, 1)).thenReturn(1);
        when(skuStockMapper.reserveStock(2002L, 1002L, 1)).thenReturn(1);

        inventoryService.reserveForCartItems(List.of(last, first, second));

        InOrder order = inOrder(skuStockMapper);
        order.verify(skuStockMapper).reserveStock(2001L, 1001L, 1);
        order.verify(skuStockMapper).reserveStock(2002L, 1001L, 1);
        order.verify(skuStockMapper).reserveStock(2002L, 1002L, 1);
    }

    @Test
    void deductRequiresExistingReservedStock() {
        OmsOrderItem item = new OmsOrderItem();
        item.setProductId(1001L);
        item.setProductSkuId(2001L);
        item.setProductQuantity(2);
        when(skuStockMapper.deductReservedStock(2001L, 1001L, 2)).thenReturn(0);

        assertThrows(
                RuntimeException.class,
                () -> inventoryService.deductForOrderItems(List.of(item))
        );
    }

    @Test
    void restoreRecordsTraceableInventoryReversal() {
        OmsOrderItem item = new OmsOrderItem();
        item.setId(3001L);
        item.setOrderId(4001L);
        item.setProductId(1001L);
        item.setProductSkuId(2001L);
        item.setProductQuantity(2);
        when(skuStockMapper.restoreStock(2001L, 1001L, 2)).thenReturn(1);

        inventoryService.restoreForOrderItems(List.of(item), "REFUND-5001");

        ArgumentCaptor<InventoryLedger> captor = ArgumentCaptor.forClass(InventoryLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        InventoryLedger ledger = captor.getValue();
        assertEquals("REFUND-5001:3001", ledger.getEventId());
        assertEquals(4001L, ledger.getOrderId());
        assertEquals(3001L, ledger.getOrderItemId());
        assertEquals(2, ledger.getOnHandDelta());
        assertEquals(-2, ledger.getSoldDelta());
    }

    @Test
    void inventoryEventsMatchCanonicalDeltaTable() {
        OmsCartItem cart = cartItem(1001L, 2001L, 2);
        cart.setId(5001L);
        OmsOrderItem order = new OmsOrderItem();
        order.setId(6001L);
        order.setOrderId(7001L);
        order.setProductId(1001L);
        order.setProductSkuId(2001L);
        order.setProductQuantity(2);
        when(skuStockMapper.reserveStock(2001L, 1001L, 2)).thenReturn(1);
        when(skuStockMapper.releaseStock(2001L, 1001L, 2)).thenReturn(1);
        when(skuStockMapper.deductReservedStock(2001L, 1001L, 2)).thenReturn(1);
        when(skuStockMapper.restoreStock(2001L, 1001L, 2)).thenReturn(1);

        inventoryService.reserveForCartItems(List.of(cart));
        inventoryService.releaseForOrderItems(List.of(order));
        inventoryService.deductForOrderItems(List.of(order));
        inventoryService.restoreForOrderItems(List.of(order), "REFUND-1");

        ArgumentCaptor<InventoryLedger> captor = ArgumentCaptor.forClass(InventoryLedger.class);
        org.mockito.Mockito.verify(ledgerMapper, org.mockito.Mockito.times(4)).insert(captor.capture());
        List<InventoryLedger> events = captor.getAllValues();
        assertDelta(events.get(0), "RESERVE", 0, 2, 0, -2);
        assertDelta(events.get(1), "RELEASE", 0, -2, 0, 2);
        assertDelta(events.get(2), "DEDUCT", -2, -2, 2, 0);
        assertDelta(events.get(3), "RESTORE", 2, 0, -2, 2);
    }

    private void assertDelta(InventoryLedger event, String operation, int onHand, int reserved,
                             int sold, int available) {
        assertEquals(operation, event.getOperation());
        assertEquals(onHand, event.getOnHandDelta());
        assertEquals(reserved, event.getReservedDelta());
        assertEquals(sold, event.getSoldDelta());
        assertEquals(available, event.getAvailableDelta());
    }

    private OmsCartItem cartItem(Long productId, Long skuId, int quantity) {
        OmsCartItem item = new OmsCartItem();
        item.setProductId(productId);
        item.setProductSkuId(skuId);
        item.setQuantity(quantity);
        return item;
    }
}
