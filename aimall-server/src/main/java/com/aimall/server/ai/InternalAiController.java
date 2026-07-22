package com.aimall.server.ai;

import cn.dev33.satoken.stp.StpUtil;
import com.aimall.server.common.ApiResponse;
import com.aimall.server.common.KnowledgeUtcTime;
import com.aimall.server.common.RequestUtils;
import com.aimall.server.ai.dto.ChunkEmbeddingUpdateRequest;
import com.aimall.server.ai.dto.ConfirmedActionRequest;
import com.aimall.server.ai.dto.ConfirmedCartAddRequest;
import com.aimall.server.ai.dto.ConfirmedCouponClaimRequest;
import com.aimall.server.ai.dto.ConfirmedOrderCancelRequest;
import com.aimall.server.ai.dto.ConfirmedReturnApplyRequest;
import com.aimall.server.ai.dto.KnowledgeChunkRetrieveRequest;
import com.aimall.server.ai.dto.VectorDeletionClaimRequest;
import com.aimall.server.ai.dto.VectorDeletionCompleteRequest;
import com.aimall.server.ai.dto.VectorDeletionFailRequest;
import com.aimall.server.entity.KnowledgeChunk;
import com.aimall.server.entity.KnowledgeDoc;
import com.aimall.server.entity.KnowledgeIndexTask;
import com.aimall.server.entity.OmsOrder;
import com.aimall.server.entity.OmsOrderItem;
import com.aimall.server.entity.OmsOrderReturnApply;
import com.aimall.server.entity.OmsCartItem;
import com.aimall.server.entity.PmsProduct;
import com.aimall.server.entity.PmsSkuStock;
import com.aimall.server.entity.UmsMemberAddress;
import com.aimall.server.mapper.OmsOrderItemMapper;
import com.aimall.server.service.AddressService;
import com.aimall.server.service.AiActionExecutionService;
import com.aimall.server.service.CartService;
import com.aimall.server.service.CouponService;
import com.aimall.server.service.KnowledgeChunkService;
import com.aimall.server.service.KnowledgeDocService;
import com.aimall.server.service.KnowledgeIndexTaskService;
import com.aimall.server.service.KnowledgeTaskExecutionGuard;
import com.aimall.server.service.OrderService;
import com.aimall.server.service.ProductService;
import com.aimall.server.service.ReturnApplyService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.Valid;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.function.Function;

@RestController
@RequestMapping("/internal/ai")
public class InternalAiController {

    private final ProductService productService;
    private final KnowledgeChunkService knowledgeChunkService;
    private final KnowledgeDocService knowledgeDocService;
    private final KnowledgeIndexTaskService knowledgeIndexTaskService;
    private final OrderService orderService;
    private final OmsOrderItemMapper orderItemMapper;
    private final CouponService couponService;
    private final ReturnApplyService returnApplyService;
    private final AddressService addressService;
    private final CartService cartService;
    private final AiActionExecutionService actionExecutionService;
    private final KnowledgeTaskExecutionGuard knowledgeTaskExecutionGuard;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.aimall.server.service.impl.KnowledgeRetrievalEpochService retrievalEpochService;

    public InternalAiController(
            ProductService productService,
            KnowledgeChunkService knowledgeChunkService,
            KnowledgeDocService knowledgeDocService,
            KnowledgeIndexTaskService knowledgeIndexTaskService,
            OrderService orderService,
            OmsOrderItemMapper orderItemMapper,
            CouponService couponService,
            ReturnApplyService returnApplyService,
            AddressService addressService,
            CartService cartService,
            AiActionExecutionService actionExecutionService,
            KnowledgeTaskExecutionGuard knowledgeTaskExecutionGuard
    ) {
        this.productService = productService;
        this.knowledgeChunkService = knowledgeChunkService;
        this.knowledgeDocService = knowledgeDocService;
        this.knowledgeIndexTaskService = knowledgeIndexTaskService;
        this.orderService = orderService;
        this.orderItemMapper = orderItemMapper;
        this.couponService = couponService;
        this.returnApplyService = returnApplyService;
        this.addressService = addressService;
        this.cartService = cartService;
        this.actionExecutionService = actionExecutionService;
        this.knowledgeTaskExecutionGuard = knowledgeTaskExecutionGuard;
    }

    @GetMapping("/products/{productId}")
    public ApiResponse<Map<String, Object>> getProduct(@PathVariable Long productId) {
        PmsProduct product = productService.getById(productId);
        if (!isVisibleProduct(product)) {
            throw new RuntimeException("商品不存在");
        }
        List<PmsSkuStock> skuStocks = productService.listSkuStocks(productId);
        Map<String, Object> data = toProductCard(product);
        data.put("description", product.getDescription());
        data.put("detailDesc", product.getDetailDesc());
        data.put("skuStocks", skuStocks.stream().map(sku -> {
            Map<String, Object> skuMap = new HashMap<>();
            skuMap.put("skuId", sku.getId());
            skuMap.put("skuCode", sku.getSkuCode());
            skuMap.put("price", sku.getPrice());
            skuMap.put("promotionPrice", sku.getPromotionPrice());
            skuMap.put("stock", availableStock(sku.getStock(), sku.getLockStock()));
            skuMap.put("spData", sku.getSpData());
            return skuMap;
        }).collect(Collectors.toList()));
        return ApiResponse.success(data);
    }

    @GetMapping("/products/{productId}/skus")
    public ApiResponse<List<Map<String, Object>>> getProductSkus(@PathVariable Long productId) {
        PmsProduct product = productService.getById(productId);
        if (!isVisibleProduct(product)) {
            throw new RuntimeException("商品不存在");
        }
        return ApiResponse.success(productService.listSkuStocks(productId).stream().map(this::toSkuCard).collect(Collectors.toList()));
    }

    @GetMapping("/products/compare")
    public ApiResponse<Map<String, Object>> compareProducts(@RequestParam("productIds") List<Long> productIds) {
        List<Long> safeIds = productIds.stream().distinct().limit(5).collect(Collectors.toList());
        if (safeIds.size() < 2) {
            throw new RuntimeException("商品对比至少需要 2 个商品 ID");
        }
        List<PmsProduct> comparableProducts = safeIds.stream()
                .map(productService::getById)
                .filter(this::isVisibleProduct)
                .collect(Collectors.toList());
        Map<Long, Integer> comparableStocks = productService.availableStocks(comparableProducts);
        List<Map<String, Object>> products = comparableProducts.stream()
                .map(product -> {
                    Map<String, Object> data = toProductCard(product, comparableStocks.getOrDefault(product.getId(), 0));
                    data.put("description", product.getDescription());
                    data.put("detailDesc", product.getDetailDesc());
                    data.put("skuCount", productService.listSkuStocks(product.getId()).size());
                    return data;
                })
                .collect(Collectors.toList());
        if (products.size() < 2) {
            throw new RuntimeException("可对比的有效商品不足 2 个");
        }
        return ApiResponse.success(Map.of(
                "products", products,
                "dimensions", List.of("价格", "库存", "品牌", "分类", "卖点", "SKU 数量")
        ));
    }

    @GetMapping("/products")
    public ApiResponse<List<Map<String, Object>>> searchProducts(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "minPrice", required = false) BigDecimal minPrice,
            @RequestParam(value = "maxPrice", required = false) BigDecimal maxPrice,
            @RequestParam(value = "inStock", required = false) Boolean inStock,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        int safeLimit = limit == null || limit <= 0 ? 10 : Math.min(limit, 20);
        List<String> tokens = keywordTokens(keyword);
        String primaryKeyword = tokens.isEmpty() ? keyword : tokens.get(0);
        List<PmsProduct> candidates = productService.search(primaryKeyword, Math.max(50, safeLimit * 5));
        Map<Long, Integer> availableStocks = productService.availableStocks(candidates);
        return ApiResponse.success(candidates.stream()
                .filter(product -> tokens.isEmpty() || matchesAnyToken(product, tokens))
                .filter(product -> categoryId == null || categoryId.equals(product.getCategoryId()))
                .filter(product -> !Boolean.TRUE.equals(inStock) || availableStocks.getOrDefault(product.getId(), 0) > 0)
                .filter(product -> minPrice == null || effectivePrice(product).compareTo(minPrice) >= 0)
                .filter(product -> maxPrice == null || effectivePrice(product).compareTo(maxPrice) <= 0)
                .limit(safeLimit)
                .map(product -> toProductCard(product, availableStocks.getOrDefault(product.getId(), 0)))
                .collect(Collectors.toList()));
    }

    @GetMapping("/knowledge")
    public ApiResponse<List<Map<String, Object>>> knowledge(
            @RequestHeader(value = "token", required = false) String token,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "topK", required = false) Integer topK,
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "retrievalSource", required = false) String retrievalSource,
            @RequestParam(value = "categoryId", required = false) Long categoryId
    ) {
        String normalized = keyword == null ? "" : keyword.trim();
        int safeTopK = topK == null || topK <= 0 ? 5 : Math.min(topK, 50);
        List<String> tokens = keywordTokens(normalized);
        Map<String, Object> scope = resolveKnowledgeScope(token);
        LocalDateTime now = LocalDateTime.now();
        if ("chunk".equalsIgnoreCase(retrievalSource)) {
            Map<Long, BigDecimal> trustScores = knowledgeDocService.listAll().stream()
                    .collect(Collectors.toMap(KnowledgeDoc::getId, this::sourceTrustScore, (left, right) -> left));
            List<Map<String, Object>> chunks = knowledgeChunkService.listActive().stream()
                    .filter(chunk -> canReadChunk(chunk, scope, categoryId, now))
                    .filter(chunk -> sourceType == null || sourceType.isBlank() || sourceType.equalsIgnoreCase(chunk.getSourceType()))
                    .map(chunk -> toChunkMap(
                            chunk,
                            normalized,
                            chunkPolicyScore(chunk, normalized, tokens),
                            trustScores.getOrDefault(chunk.getDocId(), BigDecimal.valueOf(0.5))
                    ))
                    .filter(chunk -> normalized.isEmpty() || ((Integer) chunk.get("score")) > 0)
                    .sorted(Comparator.comparing(item -> -((Integer) item.get("score"))))
                    .limit(safeTopK)
                    .collect(Collectors.toList());
            return ApiResponse.success(chunks);
        }
        List<KnowledgeDoc> currentDocs = knowledgeDocService.listAll().stream()
                .filter(doc -> "ENABLED".equalsIgnoreCase(doc.getStatus()) || "ACTIVE".equalsIgnoreCase(doc.getStatus()))
                .filter(doc -> doc.getCurrentVersionId() != null)
                .filter(doc -> canReadDoc(doc, scope, categoryId, now))
                .filter(doc -> sourceType == null || sourceType.isBlank() || sourceType.equalsIgnoreCase(doc.getSourceType()))
                .collect(Collectors.toList());
        Map<Long, List<KnowledgeChunk>> chunksByDoc = knowledgeChunkService.listActive().stream()
                .filter(chunk -> canReadChunk(chunk, scope, categoryId, now))
                .filter(chunk -> sourceType == null || sourceType.isBlank() || sourceType.equalsIgnoreCase(chunk.getSourceType()))
                .filter(chunk -> currentDocs.stream().anyMatch(doc ->
                        doc.getId().equals(chunk.getDocId())
                                && doc.getCurrentVersionId().equals(chunk.getDocVersionId())))
                .collect(Collectors.groupingBy(KnowledgeChunk::getDocId));
        List<Map<String, Object>> data = currentDocs.stream()
                .map(doc -> {
                    List<KnowledgeChunk> currentChunks = chunksByDoc.getOrDefault(doc.getId(), List.of());
                    String content = currentDocumentContent(currentChunks);
                    Map<String, Object> item = toDocMap(
                            doc, normalized, policyScore(doc, content, normalized, tokens), content
                    );
                    KnowledgeChunk sourceChunk = currentChunks.isEmpty() ? null : currentChunks.get(0);
                    item.put("publicationVersion", sourceChunk == null || sourceChunk.getPublicationVersion() == null
                            ? "" : sourceChunk.getPublicationVersion());
                    item.put("retrievalEpoch", sourceChunk == null || sourceChunk.getRetrievalEpoch() == null
                            ? 0L : sourceChunk.getRetrievalEpoch());
                    return item;
                })
                .filter(doc -> normalized.isEmpty() || ((Integer) doc.get("score")) > 0)
                .sorted(Comparator.comparing(item -> -((Integer) item.get("score"))))
                .limit(safeTopK)
                .collect(Collectors.toList());
        return ApiResponse.success(data);
    }

    @GetMapping("/knowledge/scope")
    public ApiResponse<Map<String, Object>> knowledgeScope(
            @RequestHeader(value = "token", required = false) String token
    ) {
        return ApiResponse.success(resolveKnowledgeScope(token));
    }

    @PostMapping("/knowledge/chunks/retrieve")
    public ApiResponse<List<Map<String, Object>>> retrieveKnowledgeChunks(
            @RequestHeader(value = "token", required = false) String token,
            @Valid @RequestBody KnowledgeChunkRetrieveRequest params
    ) {
        List<Long> chunkIds = params.chunkIds().stream()
                .distinct()
                .limit(100)
                .collect(Collectors.toList());
        Long categoryId = params.categoryId();
        String sourceType = params.sourceType();
        Map<String, Object> scope = resolveKnowledgeScope(token);
        LocalDateTime now = LocalDateTime.now();
        Map<Long, KnowledgeChunk> allowed = knowledgeChunkService.listByIds(chunkIds).stream()
                .filter(chunk -> canReadChunk(chunk, scope, categoryId, now))
                .filter(chunk -> sourceType == null || sourceType.isBlank() || sourceType.equalsIgnoreCase(chunk.getSourceType()))
                .collect(Collectors.toMap(KnowledgeChunk::getId, chunk -> chunk));
        Map<Long, BigDecimal> trustScores = knowledgeDocService.listAll().stream()
                .filter(doc -> allowed.values().stream().anyMatch(chunk -> chunk.getDocId().equals(doc.getId())))
                .collect(Collectors.toMap(KnowledgeDoc::getId, this::sourceTrustScore, (left, right) -> left));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Long chunkId : chunkIds) {
            KnowledgeChunk chunk = allowed.get(chunkId);
            if (chunk != null) {
                result.add(toChunkMap(
                        chunk, "", 0, trustScores.getOrDefault(chunk.getDocId(), BigDecimal.valueOf(0.5))
                ));
            }
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/knowledge/chunks")
    public ApiResponse<List<Map<String, Object>>> knowledgeChunks(
            @RequestParam(value = "syncStatus", required = false) String syncStatus,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        int safeLimit = limit == null || limit <= 0 ? 100 : Math.min(limit, 500);
        List<KnowledgeChunk> chunks = knowledgeChunkService.listForVectorSync(syncStatus, safeLimit);
        Map<Long, KnowledgeIndexTask> executionByVersion = knowledgeIndexTaskService.listRunningByDocVersionIds(
                        chunks.stream().map(KnowledgeChunk::getDocVersionId).filter(java.util.Objects::nonNull).distinct().toList()
                ).stream()
                .collect(Collectors.toMap(KnowledgeIndexTask::getDocVersionId, task -> task, (left, right) -> left));
        List<Map<String, Object>> data = chunks.stream()
                .map(chunk -> {
                    Map<String, Object> item = toChunkVectorSyncMap(chunk);
                    KnowledgeIndexTask execution = executionByVersion.get(chunk.getDocVersionId());
                    item.put("executionTaskId", execution == null ? "" : execution.getTaskId());
                    item.put("executionToken", execution == null ? "" : execution.getExecutionToken());
                    return item;
                })
                .collect(Collectors.toList());
        return ApiResponse.success(data);
    }

    @PostMapping("/knowledge/chunks/vector-deletions/claim")
    public ApiResponse<List<Map<String, Object>>> knowledgeChunkVectorDeletions(
            @Valid @RequestBody VectorDeletionClaimRequest params
    ) {
        int safeLimit = params.resolvedLimit();
        return ApiResponse.success(knowledgeChunkService.claimVectorDeletions(safeLimit).stream()
                .map(this::toChunkVectorSyncMap)
                .collect(Collectors.toList()));
    }

    @PostMapping("/knowledge/chunks/{chunkId}/vector-deletion-complete")
    public ApiResponse<Map<String, Object>> completeKnowledgeChunkVectorDeletion(
            @PathVariable Long chunkId,
            @Valid @RequestBody VectorDeletionCompleteRequest params
    ) {
        return ApiResponse.success(toChunkVectorSyncMap(knowledgeChunkService.completeVectorDeletion(
                chunkId, params.claimToken()
        )));
    }

    @PostMapping("/knowledge/chunks/{chunkId}/vector-deletion-failed")
    public ApiResponse<Map<String, Object>> failKnowledgeChunkVectorDeletion(
            @PathVariable Long chunkId,
            @Valid @RequestBody VectorDeletionFailRequest params
    ) {
        return ApiResponse.success(toChunkVectorSyncMap(knowledgeChunkService.failVectorDeletion(
                chunkId,
                params.claimToken(), params.errorMessage()
        )));
    }

    @PostMapping("/knowledge/chunks/rebuild")
    public ApiResponse<Map<String, Object>> rebuildKnowledgeChunks() {
        int docCount = 0;
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (KnowledgeDoc doc : knowledgeDocService.listAll()) {
            if (doc.getCurrentVersionId() == null || !"ACTIVE".equalsIgnoreCase(doc.getStatus())) {
                continue;
            }
            docCount += 1;
            KnowledgeIndexTask task = knowledgeIndexTaskService.createUploadDocTask(
                    doc.getId(), doc.getCurrentVersionId(), "INTERNAL_REBUILD"
            );
            tasks.add(Map.of(
                    "taskId", task.getTaskId(),
                    "docId", doc.getId(),
                    "docVersionId", doc.getCurrentVersionId(),
                    "status", task.getStatus()
            ));
        }
        return ApiResponse.success(Map.of(
                "docCount", docCount,
                "taskCount", tasks.size(),
                "tasks", tasks
        ));
    }

    @PostMapping("/knowledge/chunks/{chunkId}/embedding")
    @Transactional
    public ApiResponse<Map<String, Object>> updateKnowledgeChunkEmbedding(
            @PathVariable Long chunkId,
            @Valid @RequestBody ChunkEmbeddingUpdateRequest params
    ) {
        KnowledgeIndexTask executionTask = knowledgeTaskExecutionGuard.lockActive(
                params.executionTaskId(), params.executionToken()
        );
        if (!"RUNNING".equals(executionTask.getStatus())) {
            throw new IllegalStateException("知识任务尚未进入 RUNNING，拒绝向量状态写回");
        }
        KnowledgeChunk targetChunk = knowledgeChunkService.listByIds(List.of(chunkId)).stream().findFirst()
                .orElseThrow(() -> new RuntimeException("Knowledge chunk does not exist"));
        if (!executionTask.getDocVersionId().equals(targetChunk.getDocVersionId())) {
            throw new IllegalStateException("Chunk 不属于当前知识任务执行版本");
        }
        KnowledgeChunk chunk = knowledgeChunkService.updateEmbeddingStatus(
                chunkId,
                params.embeddingId(), params.embeddingModel(), params.embeddingModelVersion(),
                params.embeddingSyncStatus(), params.vectorCollection()
        );
        String status = params.status();
        if (status != null && !status.isBlank()) {
            chunk.setStatus(status);
            chunk = knowledgeChunkService.update(chunk);
        }
        return ApiResponse.success(toChunkVectorSyncMap(chunk));
    }

    @GetMapping("/orders")
    public ApiResponse<List<Map<String, Object>>> listOrders(
            @RequestHeader(value = "token", required = false) String token,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        long memberId = resolveMemberId(token);
        int safeLimit = limit == null || limit <= 0 ? 5 : Math.min(limit, 20);
        List<OmsOrder> orders = orderService.listByMember(memberId).stream()
                .filter(order -> status == null || status.equals(order.getStatus()))
                .limit(safeLimit)
                .collect(Collectors.toList());
        return ApiResponse.success(orders.stream().map(order -> {
            List<OmsOrderItem> items = orderItemMapper.selectList(
                    new LambdaQueryWrapper<OmsOrderItem>().eq(OmsOrderItem::getOrderId, order.getId())
            );
            return toOrderSummary(order, items);
        }).collect(Collectors.toList()));
    }

    @GetMapping("/orders/{orderRef}")
    public ApiResponse<Map<String, Object>> getOrder(
            @PathVariable String orderRef,
            @RequestHeader(value = "token", required = false) String token
    ) {
        long memberId = resolveMemberId(token);
        OmsOrder order = findOrderByRef(memberId, orderRef);
        if (order == null || order.getDeleteStatus() == 1 || order.getMemberId() == null || !order.getMemberId().equals(memberId)) {
            throw new RuntimeException("订单不存在或无权访问");
        }
        List<OmsOrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OmsOrderItem>().eq(OmsOrderItem::getOrderId, order.getId())
        );
        Map<String, Object> data = toOrderSummary(order, items);
        data.put("receiverName", order.getReceiverName() == null ? "" : order.getReceiverName());
        data.put("receiverPhone", order.getReceiverPhone() == null ? "" : maskPhone(order.getReceiverPhone()));
        data.put("deliveryCompany", order.getDeliveryCompany() == null ? "" : order.getDeliveryCompany());
        data.put("deliverySn", order.getDeliverySn() == null ? "" : order.getDeliverySn());
        return ApiResponse.success(data);
    }

    @GetMapping("/coupons/my")
    public ApiResponse<List<Map<String, Object>>> listMyCoupons(
            @RequestHeader(value = "token", required = false) String token
    ) {
        long memberId = resolveMemberId(token);
        return ApiResponse.success(couponService.listMemberCoupons(memberId));
    }

    @GetMapping("/coupons/center")
    public ApiResponse<List<Map<String, Object>>> listCouponCenter(
            @RequestHeader(value = "token", required = false) String token
    ) {
        long memberId = resolveMemberId(token);
        return ApiResponse.success(couponService.listCouponCenter(memberId));
    }

    @GetMapping("/returns")
    public ApiResponse<List<Map<String, Object>>> listReturns(
            @RequestHeader(value = "token", required = false) String token
    ) {
        long memberId = resolveMemberId(token);
        return ApiResponse.success(returnApplyService.listByMember(memberId).stream()
                .map(this::toReturnMap)
                .collect(Collectors.toList()));
    }

    @GetMapping("/returns/{returnId}")
    public ApiResponse<Map<String, Object>> getReturnDetail(
            @PathVariable Long returnId,
            @RequestHeader(value = "token", required = false) String token
    ) {
        long memberId = resolveMemberId(token);
        return ApiResponse.success(toReturnMap(returnApplyService.getByIdForMember(memberId, returnId)));
    }

    @GetMapping("/addresses")
    public ApiResponse<List<Map<String, Object>>> listAddresses(
            @RequestHeader(value = "token", required = false) String token
    ) {
        long memberId = resolveMemberId(token);
        return ApiResponse.success(addressService.listByMember(memberId).stream()
                .map(this::toAddressMap)
                .collect(Collectors.toList()));
    }

    @PostMapping("/actions/cart/add")
    public ApiResponse<Map<String, Object>> addToCartConfirmed(
            @Valid @RequestBody ConfirmedCartAddRequest params,
            @RequestHeader(value = "token", required = false) String token
    ) {
        return executeConfirmedAction(params, token, "ADD_TO_CART", memberId -> {
            OmsCartItem item = cartService.add(
                    memberId, params.productId(), params.productSkuId(), params.resolvedQuantity()
            );
            Map<String, Object> data = new HashMap<>();
            data.put("cartItemId", item.getId());
            data.put("productId", item.getProductId());
            data.put("productSkuId", item.getProductSkuId() == null ? 0L : item.getProductSkuId());
            data.put("productName", item.getProductName());
            data.put("quantity", item.getQuantity());
            return data;
        });
    }

    @PostMapping("/actions/coupons/claim")
    public ApiResponse<Map<String, Object>> claimCouponConfirmed(
            @Valid @RequestBody ConfirmedCouponClaimRequest params,
            @RequestHeader(value = "token", required = false) String token
    ) {
        return executeConfirmedAction(params, token, "CLAIM_COUPON", memberId ->
                new HashMap<>(couponService.claimCoupon(
                        memberId,
                        params.couponId()
                ))
        );
    }

    @PostMapping("/actions/orders/cancel")
    public ApiResponse<Map<String, Object>> cancelOrderConfirmed(
            @Valid @RequestBody ConfirmedOrderCancelRequest params,
            @RequestHeader(value = "token", required = false) String token
    ) {
        return executeConfirmedAction(params, token, "CANCEL_ORDER", memberId -> {
            Long orderId = params.orderId();
            orderService.cancel(memberId, orderId);
            OmsOrder current = orderService.getById(orderId);
            boolean closed = current != null && current.getStatus() != null && current.getStatus() == 4;
            Map<String, Object> data = new HashMap<>();
            data.put("orderId", orderId);
            data.put("orderSn", params.orderSn() == null ? "" : params.orderSn());
            data.put("status", closed ? 4 : 0);
            data.put("statusText", closed ? "已关闭" : "取消处理中");
            return data;
        });
    }

    @PostMapping("/actions/returns/apply")
    public ApiResponse<Map<String, Object>> applyReturnConfirmed(
            @Valid @RequestBody ConfirmedReturnApplyRequest params,
            @RequestHeader(value = "token", required = false) String token
    ) {
        return executeConfirmedAction(params, token, "APPLY_RETURN", memberId -> {
            OmsOrderReturnApply apply = returnApplyService.apply(
                    memberId,
                    params.orderId(), params.reason(), params.description()
            );
            return toReturnMap(apply);
        });
    }

    private ApiResponse<Map<String, Object>> executeConfirmedAction(
            ConfirmedActionRequest params,
            String token,
            String actionType,
            Function<Long, Map<String, Object>> operation
    ) {
        long memberId = resolveMemberId(token);
        String actionId = params.actionId();
        AiActionExecutionService.Reservation reservation = actionExecutionService.reserve(
                actionId,
                actionType,
                memberId,
                params.toPayload()
        );
        if (!reservation.shouldExecute()) {
            return ApiResponse.success(reservation.replayResult());
        }
        Map<String, Object> data;
        try {
            data = operation.apply(memberId);
        } catch (RuntimeException exception) {
            try {
                actionExecutionService.markFailed(actionId, exception.getMessage());
            } catch (Exception ignored) {
                // Preserve the original business failure for the caller.
            }
            throw exception;
        }
        data.put("actionId", actionId);
        data.put("replayed", false);
        // If result persistence fails after the business write, keep PROCESSING to block unsafe retries.
        actionExecutionService.markSuccess(actionId, data);
        return ApiResponse.success(data);
    }

    private boolean isVisibleProduct(PmsProduct product) {
        return product != null
                && product.getDeleteStatus() != null
                && product.getDeleteStatus() == 0
                && product.getPublishStatus() != null
                && product.getPublishStatus() == 1
                && product.getVerifyStatus() != null
                && product.getVerifyStatus() == 1;
    }

    private Map<String, Object> toProductCard(PmsProduct product) {
        return toProductCard(product, productService.availableStock(product));
    }

    private Map<String, Object> toProductCard(PmsProduct product, int availableStock) {
        Map<String, Object> data = new HashMap<>();
        data.put("productId", product.getId());
        data.put("name", product.getName());
        data.put("brandName", product.getBrandName() == null ? "" : product.getBrandName());
        data.put("categoryName", product.getProductCategoryName() == null ? "" : product.getProductCategoryName());
        data.put("categoryId", product.getCategoryId());
        data.put("price", effectivePrice(product));
        data.put("originalPrice", product.getOriginalPrice());
        data.put("promotionPrice", product.getPromotionPrice());
        data.put("stock", availableStock);
        data.put("pic", product.getPic() == null ? "" : product.getPic());
        data.put("subTitle", product.getSubTitle() == null ? "" : product.getSubTitle());
        data.put("keywords", product.getKeywords() == null ? "" : product.getKeywords());
        data.put("sellingPoints", sellingPoints(product));
        return data;
    }

    private Map<String, Object> toSkuCard(PmsSkuStock sku) {
        Map<String, Object> data = new HashMap<>();
        data.put("skuId", sku.getId());
        data.put("productId", sku.getProductId());
        data.put("skuCode", sku.getSkuCode());
        data.put("price", sku.getPromotionPrice() != null ? sku.getPromotionPrice() : sku.getPrice());
        data.put("originalPrice", sku.getPrice());
        data.put("promotionPrice", sku.getPromotionPrice());
        data.put("stock", availableStock(sku.getStock(), sku.getLockStock()));
        data.put("pic", sku.getPic() == null ? "" : sku.getPic());
        data.put("sale", sku.getSale());
        data.put("spData", sku.getSpData() == null ? "" : sku.getSpData());
        return data;
    }

    private Map<String, Object> toOrderSummary(OmsOrder order, List<OmsOrderItem> items) {
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", order.getId());
        data.put("orderSn", order.getOrderSn());
        data.put("status", order.getStatus());
        data.put("statusText", orderStatusText(order.getStatus()));
        data.put("totalAmount", order.getTotalAmount());
        data.put("payAmount", order.getPayAmount());
        data.put("createTime", order.getCreateTime() == null ? "" : order.getCreateTime().toString().replace("T", " "));
        data.put("itemCount", items.size());
        data.put("items", items.stream().limit(5).map(item -> {
            Map<String, Object> row = new HashMap<>();
            row.put("productId", item.getProductId());
            row.put("productName", item.getProductName());
            row.put("brand", item.getProductBrand() == null ? "" : item.getProductBrand());
            row.put("quantity", item.getProductQuantity());
            row.put("price", item.getProductPrice());
            row.put("skuCode", item.getProductSkuCode() == null ? "" : item.getProductSkuCode());
            row.put("productAttr", item.getProductAttr() == null ? "" : item.getProductAttr());
            return row;
        }).collect(Collectors.toList()));
        return data;
    }

    private Map<String, Object> toReturnMap(OmsOrderReturnApply apply) {
        Map<String, Object> data = new HashMap<>();
        data.put("returnId", apply.getId());
        data.put("orderId", apply.getOrderId());
        data.put("orderSn", apply.getOrderSn());
        data.put("type", apply.getType() == null ? "" : apply.getType());
        data.put("status", apply.getStatus());
        data.put("statusText", returnStatusText(apply.getStatus()));
        data.put("reason", apply.getReason() == null ? "" : apply.getReason());
        data.put("description", apply.getDescription() == null ? "" : apply.getDescription());
        data.put("returnAmount", apply.getReturnAmount());
        data.put("handleNote", apply.getHandleNote() == null ? "" : apply.getHandleNote());
        data.put("handleTime", apply.getHandleTime() == null ? "" : apply.getHandleTime().toString().replace("T", " "));
        data.put("createTime", apply.getCreateTime() == null ? "" : apply.getCreateTime().toString().replace("T", " "));
        data.put("updateTime", apply.getUpdateTime() == null ? "" : apply.getUpdateTime().toString().replace("T", " "));
        return data;
    }

    private Map<String, Object> toAddressMap(UmsMemberAddress address) {
        Map<String, Object> data = new HashMap<>();
        data.put("addressId", address.getId());
        data.put("name", address.getName() == null ? "" : address.getName());
        data.put("phone", address.getPhone() == null ? "" : maskPhone(address.getPhone()));
        data.put("province", address.getProvince() == null ? "" : address.getProvince());
        data.put("city", address.getCity() == null ? "" : address.getCity());
        data.put("region", address.getRegion() == null ? "" : address.getRegion());
        data.put("detailAddress", address.getDetailAddress() == null ? "" : address.getDetailAddress());
        data.put("fullAddress", String.join("",
                address.getProvince() == null ? "" : address.getProvince(),
                address.getCity() == null ? "" : address.getCity(),
                address.getRegion() == null ? "" : address.getRegion(),
                address.getDetailAddress() == null ? "" : address.getDetailAddress()
        ));
        data.put("defaultStatus", address.getDefaultStatus());
        data.put("isDefault", address.getDefaultStatus() != null && address.getDefaultStatus() == 1);
        return data;
    }

    private BigDecimal effectivePrice(PmsProduct product) {
        if (product.getPromotionPrice() != null) {
            return product.getPromotionPrice();
        }
        if (product.getPrice() != null) {
            return product.getPrice();
        }
        return BigDecimal.ZERO;
    }

    private List<String> sellingPoints(PmsProduct product) {
        return List.of(
                product.getSubTitle() == null ? "" : product.getSubTitle(),
                product.getBrandName() == null ? "" : product.getBrandName(),
                product.getKeywords() == null ? "" : product.getKeywords()
        ).stream().filter(text -> text != null && !text.isBlank()).distinct().limit(3).collect(Collectors.toList());
    }

    private List<String> keywordTokens(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String normalized = keyword
                .replaceAll("[\\p{Punct}，。！？：；（）【】]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        if (!normalized.isBlank()) {
            return enhancedKeywordTokens(normalized);
        }
        return List.of(normalized.split(" ")).stream()
                .map(String::trim)
                .filter(text -> !text.isBlank())
                .distinct()
                .limit(5)
                .collect(Collectors.toList());
    }

    private List<String> enhancedKeywordTokens(String normalized) {
        List<String> directTokens = List.of(normalized.split(" ")).stream()
                .map(String::trim)
                .filter(text -> !text.isBlank())
                .distinct()
                .collect(Collectors.toList());
        List<String> domainTokens = List.of(
                "平台", "规则", "政策", "商品", "现货", "普通现货", "发货", "发货时间", "时效", "配送", "物流", "运费",
                "购物车", "价格", "锁定", "库存", "下单", "订单", "取消", "支付", "退款", "退货", "换货", "售后",
                "优惠券", "满减", "发票", "签收", "地址"
        ).stream()
                .filter(normalized::contains)
                .collect(Collectors.toList());
        return List.of(directTokens, domainTokens).stream()
                .flatMap(List::stream)
                .distinct()
                .limit(10)
                .collect(Collectors.toList());
    }

    private boolean matchesAnyToken(PmsProduct product, List<String> tokens) {
        String text = String.join(" ",
                product.getName() == null ? "" : product.getName(),
                product.getKeywords() == null ? "" : product.getKeywords(),
                product.getSubTitle() == null ? "" : product.getSubTitle(),
                product.getDescription() == null ? "" : product.getDescription(),
                product.getBrandName() == null ? "" : product.getBrandName(),
                product.getProductCategoryName() == null ? "" : product.getProductCategoryName()
        ).toLowerCase();
        return tokens.stream().map(String::toLowerCase).anyMatch(text::contains);
    }

    private int policyScore(KnowledgeDoc doc, String content, String query, List<String> tokens) {
        if (query == null || query.isBlank()) {
            return 1;
        }
        String title = doc.getTitle() == null ? "" : doc.getTitle();
        String lowerText = (title + " " + content).toLowerCase();
        String lowerQuery = query.toLowerCase();
        int score = 0;
        if (title.toLowerCase().contains(lowerQuery)) {
            score += 20;
        }
        if (content.toLowerCase().contains(lowerQuery)) {
            score += 10;
        }
        for (String token : tokens) {
            String lowerToken = token.toLowerCase();
            if (title.toLowerCase().contains(lowerToken)) {
                score += 5;
            }
            if (lowerText.contains(lowerToken)) {
                score += 2;
            }
        }
        return score;
    }

    private int chunkPolicyScore(KnowledgeChunk chunk, String query, List<String> tokens) {
        if (query == null || query.isBlank()) {
            return 1;
        }
        String title = chunk.getTitle() == null ? "" : chunk.getTitle();
        String section = chunk.getSectionTitle() == null ? "" : chunk.getSectionTitle();
        String content = chunk.getIndexContent() == null ? "" : chunk.getIndexContent();
        String lowerText = (title + " " + section + " " + content).toLowerCase();
        String lowerQuery = query.toLowerCase();
        int score = 0;
        if (lowerText.contains(lowerQuery)) {
            score += 10;
        }
        for (String token : tokens) {
            String lowerToken = token.toLowerCase();
            if (lowerText.contains(lowerToken)) {
                score += 2;
            }
        }
        return score;
    }

    private String snippet(String content, String query) {
        if (content == null || content.isBlank()) {
            return "";
        }
        if (query == null || query.isBlank()) {
            return content.length() <= 120 ? content : content.substring(0, 120);
        }
        int index = content.toLowerCase().indexOf(query.toLowerCase());
        if (index < 0) {
            return content.length() <= 120 ? content : content.substring(0, 120);
        }
        int start = Math.max(0, index - 40);
        int end = Math.min(content.length(), index + query.length() + 80);
        return content.substring(start, end);
    }

    private long resolveMemberId(String token) {
        if (token == null || token.isBlank()) {
            throw new RuntimeException("请先登录后再查询订单");
        }
        Object loginId = StpUtil.getLoginIdByToken(token);
        if (loginId == null) {
            throw new RuntimeException("登录状态已失效，请重新登录");
        }
        return Long.parseLong(loginId.toString());
    }

    private OmsOrder findOrderByRef(long memberId, String orderRef) {
        if (orderRef == null || orderRef.isBlank()) {
            return null;
        }
        String ref = orderRef.trim();
        if (ref.matches("\\d{1,18}")) {
            OmsOrder order = orderService.getById(Long.parseLong(ref));
            if (order != null) {
                return order;
            }
        }
        return orderService.listByMember(memberId).stream()
                .filter(order -> ref.equals(order.getOrderSn()))
                .findFirst()
                .orElse(null);
    }

    private String orderStatusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 0 -> "待支付";
            case 1 -> "待发货";
            case 2 -> "已发货";
            case 3 -> "已完成";
            case 4 -> "已关闭";
            case 5 -> "无效订单";
            default -> "未知";
        };
    }

    private String returnStatusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 0 -> "待审核";
            case 1 -> "已通过";
            case 2 -> "已拒绝";
            case 3 -> "已退款";
            case 4 -> "已取消";
            case 5 -> "退款处理中";
            default -> "未知";
        };
    }

    private int availableStock(Integer stock, Integer lockStock) {
        return Math.max(0, (stock == null ? 0 : stock) - (lockStock == null ? 0 : lockStock));
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private String currentDocumentContent(List<KnowledgeChunk> chunks) {
        StringBuilder content = new StringBuilder();
        chunks.stream()
                .sorted(Comparator.comparing(KnowledgeChunk::getChunkNo))
                .map(chunk -> chunk.getMaskedContent() == null ? "" : chunk.getMaskedContent().trim())
                .filter(text -> !text.isBlank())
                .forEach(text -> {
                    if (content.length() >= 50000) {
                        return;
                    }
                    if (!content.isEmpty()) {
                        content.append("\n\n");
                    }
                    int remaining = 50000 - content.length();
                    content.append(text, 0, Math.min(text.length(), remaining));
                });
        return content.toString();
    }

    private Map<String, Object> toDocMap(KnowledgeDoc doc, String query, int score, String content) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", doc.getId());
        data.put("title", doc.getTitle());
        data.put("sourceType", doc.getSourceType());
        data.put("sourceTrustScore", sourceTrustScore(doc));
        data.put("content", content);
        data.put("snippet", snippet(content, query));
        data.put("source", doc.getSourceType() + "#" + doc.getId());
        data.put("score", score);
        data.put("version", doc.getVersion());
        data.put("updatedAt", doc.getUpdatedAt() == null ? "" : doc.getUpdatedAt().toString().replace("T", " "));
        data.put("status", doc.getStatus());
        return data;
    }

    private Map<String, Object> toChunkMap(
            KnowledgeChunk chunk, String query, int score, BigDecimal sourceTrustScore
    ) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", chunk.getId());
        data.put("chunkId", chunk.getId());
        data.put("docId", chunk.getDocId());
        data.put("docVersionId", chunk.getDocVersionId());
        data.put("title", chunk.getTitle());
        data.put("sectionTitle", chunk.getSectionTitle() == null ? "" : chunk.getSectionTitle());
        data.put("sectionPath", chunk.getSectionPath() == null ? "[]" : chunk.getSectionPath());
        data.put("pageStart", chunk.getPageStart());
        data.put("pageEnd", chunk.getPageEnd());
        data.put("sourceType", chunk.getSourceType());
        data.put("sourceTrustScore", sourceTrustScore);
        data.put("content", chunk.getMaskedContent() == null ? "" : chunk.getMaskedContent());
        data.put("snippet", chunk.getSnippet() == null || chunk.getSnippet().isBlank() ? snippet(chunk.getMaskedContent(), query) : chunk.getSnippet());
        data.put("source", chunk.getSourceRef() == null ? chunk.getSourceType() + "#" + chunk.getDocId() + "#chunk-" + chunk.getChunkNo() : chunk.getSourceRef());
        data.put("score", score);
        data.put("version", chunk.getDocVersion());
        data.put("publicationVersion", chunk.getPublicationVersion() == null ? "" : chunk.getPublicationVersion());
        data.put("retrievalEpoch", chunk.getRetrievalEpoch() == null ? 0L : chunk.getRetrievalEpoch());
        data.put("updatedAt", chunk.getUpdatedAt() == null ? "" : chunk.getUpdatedAt().toString().replace("T", " "));
        data.put("status", chunk.getStatus());
        data.put("retrievalSource", "chunk");
        data.put("contentHash", chunk.getContentHash());
        data.put("chunkKey", chunk.getChunkKey());
        data.put("chunkNo", chunk.getChunkNo());
        return data;
    }

    private BigDecimal sourceTrustScore(KnowledgeDoc doc) {
        BigDecimal score = doc.getSourceTrustScore();
        if (score == null) {
            return BigDecimal.valueOf(0.5);
        }
        return score.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    private Map<String, Object> toChunkVectorSyncMap(KnowledgeChunk chunk) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", chunk.getId());
        data.put("docId", chunk.getDocId());
        data.put("docVersionId", chunk.getDocVersionId());
        data.put("docVersion", chunk.getDocVersion());
        data.put("chunkKey", chunk.getChunkKey());
        data.put("chunkNo", chunk.getChunkNo());
        data.put("title", chunk.getTitle());
        data.put("sectionTitle", chunk.getSectionTitle() == null ? "" : chunk.getSectionTitle());
        data.put("sectionPath", chunk.getSectionPath() == null ? "[]" : chunk.getSectionPath());
        data.put("pageStart", chunk.getPageStart());
        data.put("pageEnd", chunk.getPageEnd());
        data.put("indexContent", chunk.getIndexContent() == null ? "" : chunk.getIndexContent());
        data.put("maskedContent", chunk.getMaskedContent() == null ? "" : chunk.getMaskedContent());
        data.put("contentHash", chunk.getContentHash());
        data.put("sourceType", chunk.getSourceType());
        data.put("sourceRef", chunk.getSourceRef());
        data.put("status", chunk.getStatus());
        data.put("visibilityScope", chunk.getVisibilityScope());
        data.put("tenantId", chunk.getTenantId());
        data.put("roleScope", chunk.getRoleScope() == null ? "" : chunk.getRoleScope());
        data.put("categoryIds", chunk.getCategoryIds() == null ? "" : chunk.getCategoryIds());
        data.put("effectiveTime", KnowledgeUtcTime.format(chunk.getEffectiveTime()));
        data.put("expireTime", KnowledgeUtcTime.format(chunk.getExpireTime()));
        data.put("embeddingId", chunk.getEmbeddingId());
        data.put("embeddingModel", chunk.getEmbeddingModel());
        data.put("embeddingModelVersion", chunk.getEmbeddingModelVersion());
        data.put("embeddingSyncStatus", chunk.getEmbeddingSyncStatus());
        data.put("vectorDeleteRetryCount", chunk.getVectorDeleteRetryCount());
        data.put("vectorDeleteNextRetryAt", chunk.getVectorDeleteNextRetryAt() == null ? "" : chunk.getVectorDeleteNextRetryAt().toString());
        data.put("vectorDeleteClaimToken", chunk.getVectorDeleteClaimToken() == null ? "" : chunk.getVectorDeleteClaimToken());
        data.put("vectorDeleteClaimUntil", chunk.getVectorDeleteClaimUntil() == null ? "" : chunk.getVectorDeleteClaimUntil().toString());
        data.put("vectorDeleteLastError", chunk.getVectorDeleteLastError() == null ? "" : chunk.getVectorDeleteLastError());
        data.put("vectorCollection", chunk.getVectorCollection());
        data.put("indexVersion", chunk.getIndexVersion());
        data.put("publicationVersion", chunk.getPublicationVersion() == null ? "" : chunk.getPublicationVersion());
        data.put("retrievalEpoch", chunk.getRetrievalEpoch() == null ? 0L : chunk.getRetrievalEpoch());
        return data;
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private Map<String, Object> resolveKnowledgeScope(String token) {
        String role = "PUBLIC_USER";
        boolean authenticated = false;
        if (token != null && !token.isBlank()) {
            try {
                Object loginId = StpUtil.getLoginIdByToken(token);
                if (loginId != null) {
                    authenticated = true;
                    role = loginId.toString().startsWith("admin_") ? "ADMIN" : "MEMBER";
                }
            } catch (Exception ignored) {
                authenticated = false;
            }
        }
        Map<String, Object> scope = new HashMap<>();
        scope.put("tenantId", "default");
        scope.put("role", role);
        scope.put("authenticated", authenticated);
        scope.put("retrievalEpoch", retrievalEpochService == null ? 0L : retrievalEpochService.current());
        return scope;
    }

    private boolean canReadChunk(KnowledgeChunk chunk, Map<String, Object> scope, Long categoryId, LocalDateTime now) {
        return "ACTIVE".equalsIgnoreCase(chunk.getStatus())
                && matchesTenant(chunk.getTenantId(), scope)
                && matchesVisibility(chunk.getVisibilityScope(), chunk.getRoleScope(), scope)
                && matchesEffectiveTime(chunk.getEffectiveTime(), chunk.getExpireTime(), now)
                && matchesCategory(chunk.getCategoryIds(), categoryId);
    }

    private boolean canReadDoc(KnowledgeDoc doc, Map<String, Object> scope, Long categoryId, LocalDateTime now) {
        return matchesTenant(doc.getTenantId(), scope)
                && matchesVisibility(doc.getVisibilityScope(), doc.getRoleScope(), scope)
                && matchesEffectiveTime(doc.getEffectiveTime(), doc.getExpireTime(), now)
                && matchesCategory(doc.getCategoryIds(), categoryId);
    }

    private boolean matchesTenant(String tenantId, Map<String, Object> scope) {
        String expected = String.valueOf(scope.get("tenantId"));
        return tenantId == null || tenantId.isBlank() || expected.equals(tenantId);
    }

    private boolean matchesVisibility(String visibilityScope, String roleScope, Map<String, Object> scope) {
        String visibility = visibilityScope == null || visibilityScope.isBlank() ? "PUBLIC_USER" : visibilityScope.toUpperCase();
        String role = String.valueOf(scope.get("role")).toUpperCase();
        boolean authenticated = Boolean.TRUE.equals(scope.get("authenticated"));
        boolean visible = switch (visibility) {
            case "PUBLIC", "PUBLIC_USER" -> true;
            case "AUTHENTICATED", "AUTH_USER", "MEMBER" -> authenticated;
            case "ADMIN", "ADMIN_ONLY", "PRIVATE" -> "ADMIN".equals(role);
            default -> false;
        };
        if (!visible || roleScope == null || roleScope.isBlank()) {
            return visible;
        }
        List<String> allowedRoles = switch (role) {
            case "ADMIN" -> List.of("ADMIN", "ADMIN_ONLY", "MEMBER", "AUTH_USER", "AUTHENTICATED", "PUBLIC_USER", "PUBLIC");
            case "MEMBER" -> List.of("MEMBER", "AUTH_USER", "AUTHENTICATED", "PUBLIC_USER", "PUBLIC");
            default -> List.of("PUBLIC_USER", "PUBLIC");
        };
        return List.of(roleScope.toUpperCase().split("[,;|\\s]+"))
                .stream()
                .anyMatch(allowedRoles::contains);
    }

    private boolean matchesEffectiveTime(LocalDateTime effectiveTime, LocalDateTime expireTime, LocalDateTime now) {
        return (effectiveTime == null || !effectiveTime.isAfter(now))
                && (expireTime == null || expireTime.isAfter(now));
    }

    private boolean matchesCategory(String categoryIds, Long categoryId) {
        if (categoryIds == null || categoryIds.isBlank() || "[]".equals(categoryIds.trim())) {
            return true;
        }
        if (categoryId == null) {
            return false;
        }
        return List.of(categoryIds.replaceAll("[\\[\\]\"']", "").split("[,;|\\s]+"))
                .stream()
                .anyMatch(item -> item.equals(String.valueOf(categoryId)));
    }

    private List<Long> longListValue(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(this::longValue).filter(item -> item != null).collect(Collectors.toList());
    }

    private Long longValue(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
