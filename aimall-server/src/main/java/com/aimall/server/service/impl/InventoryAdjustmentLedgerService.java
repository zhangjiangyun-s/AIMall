package com.aimall.server.service.impl;

import com.aimall.server.entity.InventoryLedger;
import com.aimall.server.mapper.InventoryLedgerMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class InventoryAdjustmentLedgerService {
    private final InventoryLedgerMapper ledgerMapper;

    public InventoryAdjustmentLedgerService(InventoryLedgerMapper ledgerMapper) {
        this.ledgerMapper = ledgerMapper;
    }

    public void record(Long productId, Long skuId, int delta) {
        InventoryLedger ledger = new InventoryLedger();
        ledger.setEventId("ADMIN-ADJUST-" + UUID.randomUUID());
        ledger.setProductId(productId);
        ledger.setSkuId(skuId);
        ledger.setOperation("ADJUST");
        ledger.setQuantity(Math.abs(delta));
        ledger.setOnHandDelta(delta);
        ledger.setReservedDelta(0);
        ledger.setSoldDelta(0);
        ledger.setAvailableDelta(delta);
        ledger.setCreateTime(LocalDateTime.now());
        ledgerMapper.insert(ledger);
    }
}
