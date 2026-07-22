package com.aimall.server.admin.dto;

import com.fasterxml.jackson.annotation.JsonSetter;

import java.math.BigDecimal;

public class ProductUpdateRequest {
    private String name;
    private Long categoryId;
    private Long brandId;
    private String brandName;
    private String productCategoryName;
    private String subTitle;
    private String keywords;
    private String description;
    private String detailDesc;
    private String pic;
    private String productSn;
    private BigDecimal price;
    private BigDecimal promotionPrice;
    private BigDecimal originalPrice;
    private Integer lowStock;
    private Integer publishStatus;
    private Integer newStatus;
    private Integer recommandStatus;
    private Integer sort;
    private String status;
    private long presence;
    private boolean stockPresent;

    private static final long NAME = 1L;
    private static final long CATEGORY_ID = 1L << 1;
    private static final long BRAND_ID = 1L << 2;
    private static final long BRAND_NAME = 1L << 3;
    private static final long CATEGORY_NAME = 1L << 4;
    private static final long SUB_TITLE = 1L << 5;
    private static final long KEYWORDS = 1L << 6;
    private static final long DESCRIPTION = 1L << 7;
    private static final long DETAIL_DESC = 1L << 8;
    private static final long PIC = 1L << 9;
    private static final long PRODUCT_SN = 1L << 10;
    private static final long PRICE = 1L << 11;
    private static final long PROMOTION_PRICE = 1L << 12;
    private static final long ORIGINAL_PRICE = 1L << 13;
    private static final long LOW_STOCK = 1L << 14;
    private static final long PUBLISH_STATUS = 1L << 15;
    private static final long NEW_STATUS = 1L << 16;
    private static final long RECOMMAND_STATUS = 1L << 17;
    private static final long SORT = 1L << 18;
    private static final long STATUS = 1L << 19;

    @JsonSetter("name") public void setName(String value) { name = value; presence |= NAME; }
    @JsonSetter("categoryId") public void setCategoryId(Long value) { categoryId = value; presence |= CATEGORY_ID; }
    @JsonSetter("brandId") public void setBrandId(Long value) { brandId = value; presence |= BRAND_ID; }
    @JsonSetter("brandName") public void setBrandName(String value) { brandName = value; presence |= BRAND_NAME; }
    @JsonSetter("productCategoryName") public void setProductCategoryName(String value) { productCategoryName = value; presence |= CATEGORY_NAME; }
    @JsonSetter("subTitle") public void setSubTitle(String value) { subTitle = value; presence |= SUB_TITLE; }
    @JsonSetter("keywords") public void setKeywords(String value) { keywords = value; presence |= KEYWORDS; }
    @JsonSetter("description") public void setDescription(String value) { description = value; presence |= DESCRIPTION; }
    @JsonSetter("detailDesc") public void setDetailDesc(String value) { detailDesc = value; presence |= DETAIL_DESC; }
    @JsonSetter("pic") public void setPic(String value) { pic = value; presence |= PIC; }
    @JsonSetter("productSn") public void setProductSn(String value) { productSn = value; presence |= PRODUCT_SN; }
    @JsonSetter("price") public void setPrice(BigDecimal value) { price = value; presence |= PRICE; }
    @JsonSetter("promotionPrice") public void setPromotionPrice(BigDecimal value) { promotionPrice = value; presence |= PROMOTION_PRICE; }
    @JsonSetter("originalPrice") public void setOriginalPrice(BigDecimal value) { originalPrice = value; presence |= ORIGINAL_PRICE; }
    @JsonSetter("lowStock") public void setLowStock(Integer value) { lowStock = value; presence |= LOW_STOCK; }
    @JsonSetter("publishStatus") public void setPublishStatus(Integer value) { publishStatus = value; presence |= PUBLISH_STATUS; }
    @JsonSetter("newStatus") public void setNewStatus(Integer value) { newStatus = value; presence |= NEW_STATUS; }
    @JsonSetter("recommandStatus") public void setRecommandStatus(Integer value) { recommandStatus = value; presence |= RECOMMAND_STATUS; }
    @JsonSetter("sort") public void setSort(Integer value) { sort = value; presence |= SORT; }
    @JsonSetter("status") public void setStatus(String value) { status = value; presence |= STATUS; }
    @JsonSetter("stock") public void setStock(Integer ignored) { stockPresent = true; }

    public String getName() { return name; }
    public Long getCategoryId() { return categoryId; }
    public Long getBrandId() { return brandId; }
    public String getBrandName() { return brandName; }
    public String getProductCategoryName() { return productCategoryName; }
    public String getSubTitle() { return subTitle; }
    public String getKeywords() { return keywords; }
    public String getDescription() { return description; }
    public String getDetailDesc() { return detailDesc; }
    public String getPic() { return pic; }
    public String getProductSn() { return productSn; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getPromotionPrice() { return promotionPrice; }
    public BigDecimal getOriginalPrice() { return originalPrice; }
    public Integer getLowStock() { return lowStock; }
    public Integer getPublishStatus() { return publishStatus; }
    public Integer getNewStatus() { return newStatus; }
    public Integer getRecommandStatus() { return recommandStatus; }
    public Integer getSort() { return sort; }
    public String getStatus() { return status; }
    public boolean isNamePresent() { return has(NAME); }
    public boolean isCategoryIdPresent() { return has(CATEGORY_ID); }
    public boolean isBrandIdPresent() { return has(BRAND_ID); }
    public boolean isBrandNamePresent() { return has(BRAND_NAME); }
    public boolean isProductCategoryNamePresent() { return has(CATEGORY_NAME); }
    public boolean isSubTitlePresent() { return has(SUB_TITLE); }
    public boolean isKeywordsPresent() { return has(KEYWORDS); }
    public boolean isDescriptionPresent() { return has(DESCRIPTION); }
    public boolean isDetailDescPresent() { return has(DETAIL_DESC); }
    public boolean isPicPresent() { return has(PIC); }
    public boolean isProductSnPresent() { return has(PRODUCT_SN); }
    public boolean isPricePresent() { return has(PRICE); }
    public boolean isPromotionPricePresent() { return has(PROMOTION_PRICE); }
    public boolean isOriginalPricePresent() { return has(ORIGINAL_PRICE); }
    public boolean isLowStockPresent() { return has(LOW_STOCK); }
    public boolean isPublishStatusPresent() { return has(PUBLISH_STATUS); }
    public boolean isNewStatusPresent() { return has(NEW_STATUS); }
    public boolean isRecommandStatusPresent() { return has(RECOMMAND_STATUS); }
    public boolean isSortPresent() { return has(SORT); }
    public boolean isStatusPresent() { return has(STATUS); }
    public boolean isStockPresent() { return stockPresent; }
    public boolean hasChanges() { return presence != 0; }
    private boolean has(long field) { return (presence & field) != 0; }
}
