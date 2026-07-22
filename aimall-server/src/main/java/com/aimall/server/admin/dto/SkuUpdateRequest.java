package com.aimall.server.admin.dto;

import com.fasterxml.jackson.annotation.JsonSetter;

import java.math.BigDecimal;

public class SkuUpdateRequest {
    private String skuCode;
    private BigDecimal price;
    private BigDecimal promotionPrice;
    private Integer lowStock;
    private String pic;
    private String spData;
    private Integer status;
    private boolean skuCodePresent;
    private boolean pricePresent;
    private boolean promotionPricePresent;
    private boolean lowStockPresent;
    private boolean picPresent;
    private boolean spDataPresent;
    private boolean statusPresent;
    private boolean stockPresent;

    @JsonSetter("skuCode")
    public void setSkuCode(String value) { skuCode = value; skuCodePresent = true; }
    @JsonSetter("price")
    public void setPrice(BigDecimal value) { price = value; pricePresent = true; }
    @JsonSetter("promotionPrice")
    public void setPromotionPrice(BigDecimal value) { promotionPrice = value; promotionPricePresent = true; }
    @JsonSetter("lowStock")
    public void setLowStock(Integer value) { lowStock = value; lowStockPresent = true; }
    @JsonSetter("pic")
    public void setPic(String value) { pic = value; picPresent = true; }
    @JsonSetter("spData")
    public void setSpData(String value) { spData = value; spDataPresent = true; }
    @JsonSetter("status")
    public void setStatus(Integer value) { status = value; statusPresent = true; }
    @JsonSetter("stock")
    public void setStock(Integer ignored) { stockPresent = true; }

    public String getSkuCode() { return skuCode; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getPromotionPrice() { return promotionPrice; }
    public Integer getLowStock() { return lowStock; }
    public String getPic() { return pic; }
    public String getSpData() { return spData; }
    public Integer getStatus() { return status; }
    public boolean isSkuCodePresent() { return skuCodePresent; }
    public boolean isPricePresent() { return pricePresent; }
    public boolean isPromotionPricePresent() { return promotionPricePresent; }
    public boolean isLowStockPresent() { return lowStockPresent; }
    public boolean isPicPresent() { return picPresent; }
    public boolean isSpDataPresent() { return spDataPresent; }
    public boolean isStatusPresent() { return statusPresent; }
    public boolean isStockPresent() { return stockPresent; }
}
