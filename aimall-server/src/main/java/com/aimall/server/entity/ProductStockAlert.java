package com.aimall.server.entity;

import lombok.Data;

@Data
public class ProductStockAlert {
    private Long productId;
    private Long skuId;
    private String productName;
    private String skuCode;
    private Integer availableStock;
    private Integer lowStock;
    private Integer publishStatus;
}
