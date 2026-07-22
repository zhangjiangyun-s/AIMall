package com.aimall.server.service;

import com.aimall.server.entity.KnowledgeDoc;
import com.aimall.server.entity.OmsOrder;
import com.aimall.server.entity.PmsProduct;
import com.aimall.server.admin.dto.ProductUpdateRequest;
import com.aimall.server.common.PageResult;

import java.util.List;
import java.util.Map;

public interface AdminService {
    List<PmsProduct> listProducts();

    PageResult<PmsProduct> pageProducts(String keyword, Integer page, Integer size);

    PmsProduct createProduct(PmsProduct product);

    PmsProduct updateProduct(Long productId, ProductUpdateRequest request);

    PmsProduct changeProductPublishStatus(Long productId, boolean published);

    PmsProduct adjustProductStock(Long productId, int delta);

    void deleteProduct(Long id);

    List<KnowledgeDoc> listDocs();

    void deleteDoc(Long id);

    void rebuildDocs();

    List<OmsOrder> listOrders();

    OmsOrder getOrderById(Long orderId);

    Map<String, Object> dashboard();
}
