package com.aimall.server.entity;

import lombok.Data;

@Data
public class InventoryBalance {
    private String inventoryType;
    private Long inventoryId;
    private Long productId;
    private Integer onHand;
    private Integer reserved;
    private Integer sold;
    private Integer available;
    private Integer enabled;
}
