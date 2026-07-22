package com.aimall.server.service.impl;

import com.aimall.server.entity.OmsCartItem;
import com.aimall.server.entity.OmsOrderItem;
import com.aimall.server.mapper.PmsProductMapper;
import com.aimall.server.mapper.PmsSkuStockMapper;
import com.aimall.server.mapper.InventoryLedgerMapper;
import com.aimall.server.entity.InventoryLedger;
import com.aimall.server.outbox.OutboxEventType;
import com.aimall.server.service.InventoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.Comparator;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class InventoryServiceImpl implements InventoryService {

    private final PmsSkuStockMapper skuStockMapper;
    private final PmsProductMapper productMapper;
    private final InventoryLedgerMapper ledgerMapper;
    @Autowired(required = false)
    private OutboxEventService outboxEventService;

    public InventoryServiceImpl(PmsSkuStockMapper skuStockMapper, PmsProductMapper productMapper) {
        this(skuStockMapper, productMapper, null);
    }

    @Autowired
    public InventoryServiceImpl(PmsSkuStockMapper skuStockMapper, PmsProductMapper productMapper,
                                InventoryLedgerMapper ledgerMapper) {
        this.skuStockMapper = skuStockMapper;
        this.productMapper = productMapper;
        this.ledgerMapper = ledgerMapper;
    }

    @Override
    @Transactional
    public void reserveForCartItems(List<OmsCartItem> cartItems) {
        for (OmsCartItem item : cartItems.stream().sorted(Comparator
                .comparing(OmsCartItem::getProductId)
                .thenComparing(item -> item.getProductSkuId() == null ? Long.MIN_VALUE : item.getProductSkuId())
                .thenComparing(item -> item.getId() == null ? Long.MIN_VALUE : item.getId())).toList()) {
            reserve(item.getProductId(), item.getProductSkuId(), item.getQuantity(),
                    item.getId() == null ? UUID.randomUUID().toString() : "CART-" + item.getId(), null);
        }
    }

    @Override
    @Transactional
    public void releaseForOrderItems(List<OmsOrderItem> orderItems) {
        for (OmsOrderItem item : sortedOrderItems(orderItems)) {
            release(item.getProductId(), item.getProductSkuId(), item.getProductQuantity(),
                    item.getId() == null ? UUID.randomUUID().toString() : "ORDER-" + item.getId(), item.getOrderId());
        }
    }

    @Override
    @Transactional
    public void deductForOrderItems(List<OmsOrderItem> orderItems) {
        for (OmsOrderItem item : sortedOrderItems(orderItems)) {
            deduct(item.getProductId(), item.getProductSkuId(), item.getProductQuantity(),
                    item.getId() == null ? UUID.randomUUID().toString() : "ORDER-" + item.getId(), item.getOrderId());
        }
    }

    @Override
    @Transactional
    public void restoreForOrderItems(List<OmsOrderItem> orderItems) {
        restoreForOrderItems(orderItems, UUID.randomUUID().toString());
    }

    @Override
    @Transactional
    public void restoreForOrderItems(List<OmsOrderItem> orderItems, String operationId) {
        for (OmsOrderItem item : sortedOrderItems(orderItems)) {
            int quantity = safeQuantity(item.getProductQuantity());
            requirePositiveQuantity(quantity);
            if (item.getProductSkuId() != null
                    && skuStockMapper.restoreStock(item.getProductSkuId(), item.getProductId(), quantity) != 1) {
                throw new RuntimeException("SKU 库存恢复失败");
            }
            if (item.getProductSkuId() == null && productMapper.restoreStock(item.getProductId(), quantity) != 1) {
                throw new RuntimeException("商品库存恢复失败");
            }
            record(item.getProductId(), item.getProductSkuId(), item.getOrderId(), item.getId(),
                    "RESTORE", quantity, quantity, 0, -quantity, quantity, operationId + ":" + item.getId());
        }
    }

    private void reserve(Long productId, Long productSkuId, Integer quantity, String eventId, Long orderId) {
        int qty = safeQuantity(quantity);
        requirePositiveQuantity(qty);
        if (productSkuId != null) {
            if (skuStockMapper.reserveStock(productSkuId, productId, qty) != 1) {
                throw new RuntimeException("商品库存不足");
            }
        } else if (productMapper.reserveStock(productId, qty) != 1) {
            throw new RuntimeException("商品库存不足或已下架");
        }
        record(productId, productSkuId, orderId, null, "RESERVE", qty, 0, qty, 0, -qty, eventId);
        enqueueInventoryEvent(OutboxEventType.INVENTORY_RESERVED, productId, productSkuId, orderId,
                qty, "INVENTORY_RESERVED:" + eventId);
    }

    private void release(Long productId, Long productSkuId, Integer quantity, String eventId, Long orderId) {
        int qty = safeQuantity(quantity);
        requirePositiveQuantity(qty);
        if (productSkuId != null) {
            if (skuStockMapper.releaseStock(productSkuId, productId, qty) != 1) {
                throw new RuntimeException("SKU 锁定库存释放失败");
            }
        }
        if (productSkuId == null && productMapper.releaseStock(productId, qty) != 1) {
            throw new RuntimeException("商品锁定库存释放失败");
        }
        record(productId, productSkuId, orderId, null, "RELEASE", qty, 0, -qty, 0, qty, "RELEASE:" + eventId);
        enqueueInventoryEvent(OutboxEventType.INVENTORY_RELEASED, productId, productSkuId, orderId,
                qty, "INVENTORY_RELEASED:" + eventId);
    }

    private void deduct(Long productId, Long productSkuId, Integer quantity, String eventId, Long orderId) {
        int qty = safeQuantity(quantity);
        requirePositiveQuantity(qty);

        if (productSkuId != null) {
            if (skuStockMapper.deductReservedStock(productSkuId, productId, qty) != 1) {
                throw new RuntimeException("商品库存不足");
            }
        }
        if (productSkuId == null && productMapper.deductReservedStock(productId, qty) != 1) {
            throw new RuntimeException("商品库存不足");
        }
        record(productId, productSkuId, orderId, null, "DEDUCT", qty, -qty, -qty, qty, 0, "DEDUCT:" + eventId);
    }

    private void record(Long productId, Long skuId, Long orderId, Long orderItemId, String operation,
                        int quantity, int onHandDelta, int reservedDelta, int soldDelta,
                        int availableDelta, String eventId) {
        if (ledgerMapper == null) {
            return;
        }
        InventoryLedger ledger = new InventoryLedger();
        ledger.setEventId(eventId);
        ledger.setProductId(productId);
        ledger.setSkuId(skuId);
        ledger.setOrderId(orderId);
        ledger.setOrderItemId(orderItemId);
        ledger.setOperation(operation);
        ledger.setQuantity(quantity);
        ledger.setOnHandDelta(onHandDelta);
        ledger.setReservedDelta(reservedDelta);
        ledger.setSoldDelta(soldDelta);
        ledger.setAvailableDelta(availableDelta);
        ledger.setCreateTime(LocalDateTime.now());
        ledgerMapper.insert(ledger);
    }

    private void enqueueInventoryEvent(OutboxEventType type, Long productId, Long skuId,
                                       Long orderId, int quantity, String idempotencyKey) {
        if (outboxEventService == null) return;
        String aggregateId = skuId == null ? "PRODUCT:" + productId : "SKU:" + skuId;
        outboxEventService.enqueue("INVENTORY", aggregateId, 1L, type, idempotencyKey, 1,
                java.util.Map.of(
                        "productId", productId,
                        "skuId", skuId == null ? "" : skuId,
                        "orderId", orderId == null ? "" : orderId,
                        "quantity", quantity
                ));
    }

    private int safeQuantity(Integer value) {
        return value == null ? 0 : value;
    }

    private void requirePositiveQuantity(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("库存操作数量必须大于 0");
        }
    }

    private List<OmsOrderItem> sortedOrderItems(List<OmsOrderItem> items) {
        return items.stream().sorted(Comparator
                .comparing(OmsOrderItem::getProductId)
                .thenComparing(item -> item.getProductSkuId() == null ? Long.MIN_VALUE : item.getProductSkuId())
                .thenComparing(item -> item.getId() == null ? Long.MIN_VALUE : item.getId())).toList();
    }
}
