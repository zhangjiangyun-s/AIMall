package com.aimall.server.admin;

import cn.dev33.satoken.secure.BCrypt;
import cn.dev33.satoken.stp.StpUtil;
import com.aimall.server.common.ApiResponse;
import com.aimall.server.common.RequestUtils;
import com.aimall.server.common.ClientIpResolver;
import com.aimall.server.common.PageResult;
import com.aimall.server.admin.dto.ProductUpdateRequest;
import com.aimall.server.admin.dto.AdminLoginRequest;
import com.aimall.server.admin.dto.MfaOtpRequest;
import com.aimall.server.admin.dto.ProductCreateRequest;
import com.aimall.server.admin.dto.StockAdjustmentRequest;
import com.aimall.server.admin.dto.ShipmentRequest;
import com.aimall.server.admin.dto.LogisticsEventRequest;
import com.aimall.server.entity.KnowledgeChunk;
import com.aimall.server.entity.KnowledgeDocAuditLog;
import com.aimall.server.entity.KnowledgeIndexTask;
import com.aimall.server.entity.KnowledgeDoc;
import com.aimall.server.entity.OmsOrder;
import com.aimall.server.entity.OmsOrderItem;
import com.aimall.server.entity.PmsProduct;
import com.aimall.server.entity.UmsAdmin;
import com.aimall.server.mapper.OmsOrderItemMapper;
import com.aimall.server.mapper.UmsAdminMapper;
import com.aimall.server.service.AdminService;
import com.aimall.server.service.KnowledgeChunkService;
import com.aimall.server.service.KnowledgeDocAuditLogService;
import com.aimall.server.service.KnowledgeIndexTaskService;
import com.aimall.server.service.ProductService;
import com.aimall.server.service.AdminLoginGuardService;
import com.aimall.server.service.AdminMfaService;
import com.aimall.server.service.LogisticsService;
import com.aimall.server.service.impl.KnowledgeTaskRecoveryService;
import com.aimall.server.security.AdminPermissions;
import com.aimall.server.security.RequireAdminPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/admin")
@RequireAdminPermission(AdminPermissions.ADMIN_ACCESS)
public class AdminController {

    private final AdminService adminService;
    private final UmsAdminMapper adminMapper;
    private final OmsOrderItemMapper orderItemMapper;
    private final KnowledgeChunkService chunkService;
    private final KnowledgeIndexTaskService indexTaskService;
    private final KnowledgeTaskRecoveryService taskRecoveryService;
    private final KnowledgeDocAuditLogService auditLogService;
    private final ProductService productService;
    private final AdminLoginGuardService loginGuardService;
    private final AdminMfaService mfaService;
    private final LogisticsService logisticsService;
    private final ClientIpResolver clientIpResolver;

    public AdminController(
            AdminService adminService,
            UmsAdminMapper adminMapper,
            OmsOrderItemMapper orderItemMapper,
            KnowledgeChunkService chunkService,
            KnowledgeIndexTaskService indexTaskService,
            KnowledgeTaskRecoveryService taskRecoveryService,
            KnowledgeDocAuditLogService auditLogService,
            ProductService productService,
            AdminLoginGuardService loginGuardService,
            AdminMfaService mfaService,
            LogisticsService logisticsService,
            ClientIpResolver clientIpResolver
    ) {
        this.adminService = adminService;
        this.adminMapper = adminMapper;
        this.orderItemMapper = orderItemMapper;
        this.chunkService = chunkService;
        this.indexTaskService = indexTaskService;
        this.taskRecoveryService = taskRecoveryService;
        this.auditLogService = auditLogService;
        this.productService = productService;
        this.loginGuardService = loginGuardService;
        this.mfaService = mfaService;
        this.logisticsService = logisticsService;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        StpUtil.logout();
        return ApiResponse.success(null);
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(
            @Valid @RequestBody AdminLoginRequest params,
            HttpServletRequest servletRequest
    ) {
        String username = params.username();
        String password = params.password();
        String clientIp = clientIpResolver.resolve(servletRequest);
        loginGuardService.checkAllowed(username, clientIp);
        UmsAdmin admin = adminMapper.selectOne(
                new LambdaQueryWrapper<UmsAdmin>().eq(UmsAdmin::getUsername, username)
        );
        if (admin == null || !BCrypt.checkpw(password, admin.getPassword())) {
            loginGuardService.recordFailure(username, clientIp);
            throw new RuntimeException("用户名或密码错误");
        }
        if (admin.getStatus() == null || admin.getStatus() != 1) {
            throw new RuntimeException("管理员账号不可用");
        }
        if (admin.getMfaEnabled() != null && admin.getMfaEnabled() == 1) {
            if (!mfaService.verify(admin.getMfaSecret(), params.otp())) {
                loginGuardService.recordFailure(username, clientIp);
                throw new RuntimeException("动态验证码错误");
            }
        }
        loginGuardService.recordSuccess(username, clientIp);
        StpUtil.login("admin_" + admin.getId());
        Map<String, Object> data = new HashMap<>();
        data.put("token", StpUtil.getTokenValue());
        Map<String, Object> adminInfo = new HashMap<>();
        adminInfo.put("id", admin.getId());
        adminInfo.put("username", admin.getUsername());
        adminInfo.put("nickName", admin.getNickName() == null ? admin.getUsername() : admin.getNickName());
        adminInfo.put("mfaEnabled", admin.getMfaEnabled() != null && admin.getMfaEnabled() == 1);
        data.put("adminInfo", adminInfo);
        return ApiResponse.success(data);
    }

    @PostMapping("/mfa/setup")
    public ApiResponse<Map<String, Object>> setupMfa() {
        Long adminId = currentAdminId();
        String secret = mfaService.prepare(adminId);
        return ApiResponse.success(Map.of("secret", secret, "issuer", "AIMall", "account", StpUtil.getLoginIdAsString()));
    }

    @PostMapping("/mfa/enable")
    public ApiResponse<Void> enableMfa(@Valid @RequestBody MfaOtpRequest params) {
        mfaService.enable(currentAdminId(), params.otp());
        return ApiResponse.success(null);
    }

    @PostMapping("/mfa/disable")
    public ApiResponse<Void> disableMfa(@Valid @RequestBody MfaOtpRequest params) {
        mfaService.disable(currentAdminId(), params.otp());
        return ApiResponse.success(null);
    }

    private Long currentAdminId() {
        String loginId = StpUtil.getLoginIdAsString();
        if (loginId == null || !loginId.startsWith("admin_")) {
            throw new RuntimeException("无管理员权限");
        }
        return Long.parseLong(loginId.substring("admin_".length()));
    }

    @GetMapping("/products")
    @RequireAdminPermission(AdminPermissions.PRODUCT_VIEW)
    public ApiResponse<PageResult<Map<String, Object>>> listProducts(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size
    ) {
        PageResult<PmsProduct> productPage = adminService.pageProducts(keyword, page, size);
        List<PmsProduct> products = productPage.getList();
        Map<Long, Integer> stocks = productService.availableStocks(products);
        List<Map<String, Object>> data = products.stream()
                .map(product -> toAdminProduct(product, stocks.getOrDefault(product.getId(), 0)))
                .collect(Collectors.toList());
        return ApiResponse.success(PageResult.of(
                data, productPage.getTotal(), productPage.getPage(), productPage.getSize()
        ));
    }

    @GetMapping("/products/categories")
    @RequireAdminPermission(AdminPermissions.PRODUCT_VIEW)
    public ApiResponse<List<Map<String, Object>>> listProductCategories() {
        return ApiResponse.success(productService.listHomeCategories().stream()
                .map(category -> Map.<String, Object>of("id", category.getId(), "name", category.getName()))
                .collect(Collectors.toList()));
    }

    @PostMapping("/products")
    @RequireAdminPermission(AdminPermissions.PRODUCT_EDIT)
    public ApiResponse<Map<String, Object>> createProduct(@Valid @RequestBody ProductCreateRequest params) {
        PmsProduct product = params.toEntity();
        adminService.createProduct(product);
        return ApiResponse.success(toAdminProduct(product));
    }

    @PutMapping("/products/{id}")
    @RequireAdminPermission(AdminPermissions.PRODUCT_EDIT)
    public ApiResponse<Map<String, Object>> updateProduct(
            @PathVariable Long id,
            @RequestBody ProductUpdateRequest request
    ) {
        PmsProduct updated = adminService.updateProduct(id, request);
        return ApiResponse.success(toAdminProduct(updated));
    }

    @PostMapping("/products/{id}/stock-adjustments")
    @RequireAdminPermission(AdminPermissions.STOCK_ADJUST)
    public ApiResponse<Map<String, Object>> adjustProductStock(
            @PathVariable Long id,
            @Valid @RequestBody StockAdjustmentRequest params
    ) {
        return ApiResponse.success(toAdminProduct(
                adminService.adjustProductStock(id, params.delta())
        ));
    }

    @DeleteMapping("/products/{id}")
    @RequireAdminPermission(AdminPermissions.PRODUCT_EDIT)
    public ApiResponse<Void> deleteProduct(@PathVariable Long id) {
        adminService.deleteProduct(id);
        return ApiResponse.success(null);
    }

    @GetMapping("/knowledge-docs")
    @RequireAdminPermission(AdminPermissions.KNOWLEDGE_VIEW)
    public ApiResponse<List<Map<String, Object>>> listDocs() {
        List<Map<String, Object>> data = adminService.listDocs().stream().map(doc -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", doc.getId());
            item.put("title", doc.getTitle());
            item.put("sourceType", doc.getSourceType());
            item.put("content", doc.getContent());
            item.put("status", doc.getStatus());
            item.put("version", doc.getVersion());
            item.put("currentVersionId", doc.getCurrentVersionId());
            item.put("sourceSystem", doc.getSourceSystem() == null ? "" : doc.getSourceSystem());
            item.put("sourceUri", doc.getSourceUri() == null ? "" : doc.getSourceUri());
            item.put("sourceHash", doc.getSourceHash() == null ? "" : doc.getSourceHash());
            item.put("externalDocId", doc.getExternalDocId() == null ? "" : doc.getExternalDocId());
            item.put("visibilityScope", doc.getVisibilityScope() == null ? "" : doc.getVisibilityScope());
            item.put("tenantId", doc.getTenantId() == null ? "" : doc.getTenantId());
            item.put("roleScope", doc.getRoleScope() == null ? "" : doc.getRoleScope());
            item.put("categoryIds", doc.getCategoryIds() == null ? "" : doc.getCategoryIds());
            item.put("activityId", doc.getActivityId() == null ? "" : doc.getActivityId());
            item.put("tags", doc.getTags() == null ? "" : doc.getTags());
            item.put("ownerUserId", doc.getOwnerUserId());
            item.put("effectiveTime", formatTime(doc.getEffectiveTime()));
            item.put("expireTime", formatTime(doc.getExpireTime()));
            item.put("createdAt", formatTime(doc.getCreatedAt()));
            item.put("updatedAt", doc.getUpdatedAt() == null ? "" : doc.getUpdatedAt().toString().replace("T", " "));
            return item;
        }).collect(Collectors.toList());
        return ApiResponse.success(data);
    }

    @DeleteMapping("/knowledge-docs/{id}")
    @RequireAdminPermission(AdminPermissions.KNOWLEDGE_EDIT)
    public ApiResponse<Void> deleteDoc(@PathVariable Long id) {
        adminService.deleteDoc(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/knowledge-docs/rebuild")
    @RequireAdminPermission(AdminPermissions.KNOWLEDGE_EDIT)
    public ApiResponse<Map<String, Object>> rebuildDocs() {
        adminService.rebuildDocs();
        return ApiResponse.success(Map.of("success", true, "message", "已触发知识库重建"));
    }

    @GetMapping("/knowledge-index-tasks")
    @RequireAdminPermission(AdminPermissions.KNOWLEDGE_VIEW)
    public ApiResponse<List<Map<String, Object>>> listKnowledgeIndexTasks(
            @RequestParam(value = "limit", defaultValue = "50") Integer limit
    ) {
        List<Map<String, Object>> data = indexTaskService.listRecent(limit).stream()
                .map(this::toKnowledgeIndexTaskMap)
                .collect(Collectors.toList());
        return ApiResponse.success(data);
    }

    @PostMapping("/knowledge-index-tasks/{id}/retry")
    @RequireAdminPermission(AdminPermissions.KNOWLEDGE_EDIT)
    public ApiResponse<Map<String, Object>> retryKnowledgeIndexTask(@PathVariable Long id) {
        KnowledgeIndexTask task = taskRecoveryService.retryManually(id);
        return ApiResponse.success(toKnowledgeIndexTaskMap(task));
    }

    @GetMapping("/knowledge-docs/{id}/audit-logs")
    @RequireAdminPermission(AdminPermissions.KNOWLEDGE_VIEW)
    public ApiResponse<List<Map<String, Object>>> listKnowledgeDocAuditLogs(@PathVariable Long id) {
        List<Map<String, Object>> data = auditLogService.listByDocId(id).stream()
                .map(this::toKnowledgeDocAuditLogMap)
                .collect(Collectors.toList());
        return ApiResponse.success(data);
    }

    @GetMapping("/knowledge-docs/{id}/chunks")
    @RequireAdminPermission(AdminPermissions.KNOWLEDGE_VIEW)
    public ApiResponse<List<Map<String, Object>>> listKnowledgeDocChunks(@PathVariable Long id) {
        List<Map<String, Object>> data = chunkService.listByDocId(id).stream()
                .map(this::toKnowledgeChunkMap)
                .collect(Collectors.toList());
        return ApiResponse.success(data);
    }

    @GetMapping("/knowledge-ops/health")
    @RequireAdminPermission(AdminPermissions.ADMIN_DIAGNOSTICS)
    public ApiResponse<Map<String, Object>> knowledgeOpsHealth() {
        Map<String, Object> data = new HashMap<>();
        data.put("docCount", adminService.listDocs().size());
        data.put("activeChunkCount", chunkService.countActive());
        data.put("pendingTaskCount", indexTaskService.countByStatus("PENDING"));
        data.put("runningTaskCount", indexTaskService.countByStatus("RUNNING"));
        data.put("failedTaskCount", indexTaskService.countByStatus("FAILED"));
        data.put("retryWaitTaskCount", indexTaskService.countByStatus("RETRY_WAIT"));
        data.put("deadLetterTaskCount", indexTaskService.countByStatus("DEAD_LETTER"));
        data.put("recentTasks", indexTaskService.listRecent(10).stream().map(this::toKnowledgeIndexTaskMap).collect(Collectors.toList()));
        return ApiResponse.success(data);
    }

    @GetMapping("/orders")
    @RequireAdminPermission(AdminPermissions.ORDER_VIEW)
    public ApiResponse<List<Map<String, Object>>> listOrders() {
        List<Map<String, Object>> data = adminService.listOrders().stream().map(this::toAdminOrder).collect(Collectors.toList());
        return ApiResponse.success(data);
    }

    @GetMapping("/orders/{id}")
    @RequireAdminPermission(AdminPermissions.ORDER_VIEW)
    public ApiResponse<Map<String, Object>> orderDetail(@PathVariable Long id) {
        OmsOrder order = adminService.getOrderById(id);
        List<OmsOrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OmsOrderItem>().eq(OmsOrderItem::getOrderId, id)
        );
        Map<String, Object> data = toAdminOrder(order);
        data.put("memberUsername", order.getMemberUsername() == null ? "" : order.getMemberUsername());
        data.put("freightAmount", order.getFreightAmount());
        data.put("promotionAmount", order.getPromotionAmount());
        data.put("couponAmount", order.getCouponAmount());
        data.put("discountAmount", order.getDiscountAmount());
        data.put("note", order.getNote() == null ? "" : order.getNote());
        data.put("receiverProvince", order.getReceiverProvince() == null ? "" : order.getReceiverProvince());
        data.put("receiverCity", order.getReceiverCity() == null ? "" : order.getReceiverCity());
        data.put("receiverRegion", order.getReceiverRegion() == null ? "" : order.getReceiverRegion());
        data.put("receiverDetailAddress", order.getReceiverDetailAddress() == null ? "" : order.getReceiverDetailAddress());
        data.put("paymentTime", formatTime(order.getPaymentTime()));
        data.put("receiveTime", formatTime(order.getReceiveTime()));
        data.put("items", items.stream().map(item -> {
            Map<String, Object> row = new HashMap<>();
            row.put("productId", item.getProductId());
            row.put("productName", item.getProductName());
            row.put("productBrand", item.getProductBrand() == null ? "" : item.getProductBrand());
            row.put("quantity", item.getProductQuantity());
            row.put("price", item.getProductPrice());
            row.put("skuCode", item.getProductSkuCode() == null ? "" : item.getProductSkuCode());
            row.put("productAttr", item.getProductAttr() == null ? "" : item.getProductAttr());
            row.put("realAmount", item.getRealAmount());
            return row;
        }).collect(Collectors.toList()));
        return ApiResponse.success(data);
    }

    @PostMapping("/orders/{id}/ship")
    @RequireAdminPermission(AdminPermissions.ORDER_SHIP)
    public ApiResponse<Map<String, Object>> shipOrder(
            @PathVariable Long id,
            @Valid @RequestBody ShipmentRequest params
    ) {
        Map<String, Object> shipment = logisticsService.shipWholeOrder(
                id,
                params.resolvedCarrierCode(),
                params.deliveryCompany(),
                params.deliverySn()
        );
        return ApiResponse.success(shipment);
    }

    @PostMapping("/shipments/{shipmentId}/events")
    @RequireAdminPermission(AdminPermissions.ORDER_SHIP)
    public ApiResponse<Void> appendLogisticsEvent(
            @PathVariable Long shipmentId,
            @Valid @RequestBody LogisticsEventRequest params
    ) {
        logisticsService.appendEvent(
                shipmentId,
                params.eventCode(),
                null,
                params.location(),
                params.description()
        );
        return ApiResponse.success(null);
    }

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard() {
        Map<String, Object> data = adminService.dashboard();
        data.put("vectorDeletePendingCount", chunkService.countByEmbeddingSyncStatus("DELETE_PENDING"));
        data.put("vectorDeleteProcessingCount", chunkService.countByEmbeddingSyncStatus("DELETE_PROCESSING"));
        data.put("vectorDeleteDeadCount", chunkService.countByEmbeddingSyncStatus("DELETE_DEAD"));
        return ApiResponse.success(data);
    }

    private void fillProduct(PmsProduct product, Map<String, Object> params) {
        if (params.containsKey("name")) product.setName(RequestUtils.requiredString(params, "name"));
        if (params.containsKey("categoryId")) product.setCategoryId(RequestUtils.requiredLong(params, "categoryId"));
        if (params.containsKey("category")) product.setProductCategoryName(RequestUtils.optionalString(params, "category"));
        if (params.containsKey("price")) product.setPrice(RequestUtils.optionalDecimal(params, "price"));
        if (params.containsKey("promotionPrice")) product.setPromotionPrice(RequestUtils.optionalDecimal(params, "promotionPrice"));
        if (params.containsKey("originalPrice")) product.setOriginalPrice(RequestUtils.optionalDecimal(params, "originalPrice"));
        if (params.containsKey("stock")) product.setStock(RequestUtils.optionalInt(params, "stock"));
        if (params.containsKey("lowStock")) product.setLowStock(RequestUtils.optionalInt(params, "lowStock"));
        if (params.containsKey("description")) product.setDescription(RequestUtils.optionalString(params, "description"));
        if (params.containsKey("detailDesc")) product.setDetailDesc(RequestUtils.optionalString(params, "detailDesc"));
        if (params.containsKey("keywords")) product.setKeywords(RequestUtils.optionalString(params, "keywords"));
        if (params.containsKey("subTitle")) product.setSubTitle(RequestUtils.optionalString(params, "subTitle"));
        if (params.containsKey("brandName")) product.setBrandName(RequestUtils.optionalString(params, "brandName"));
        if (params.containsKey("productSn")) product.setProductSn(RequestUtils.optionalString(params, "productSn"));
        if (params.containsKey("publishStatus")) product.setPublishStatus(RequestUtils.optionalInt(params, "publishStatus"));
        if (params.containsKey("newStatus")) product.setNewStatus(RequestUtils.optionalInt(params, "newStatus"));
        if (params.containsKey("recommandStatus")) product.setRecommandStatus(RequestUtils.optionalInt(params, "recommandStatus"));
        if (params.containsKey("sort")) product.setSort(RequestUtils.optionalInt(params, "sort"));
        if (params.containsKey("status")) {
            Object status = params.get("status");
            if (status instanceof String) {
                if ("上架".equals(status)) {
                    product.setPublishStatus(1);
                } else if ("下架".equals(status)) {
                    product.setPublishStatus(0);
                } else {
                    throw new IllegalArgumentException("商品状态只能是上架或下架");
                }
            } else {
                product.setPublishStatus(RequestUtils.requiredInt(params, "status"));
            }
        }
    }

    private Map<String, Object> toAdminProduct(PmsProduct product) {
        return toAdminProduct(product, productService.availableStock(product));
    }

    private Map<String, Object> toAdminProduct(PmsProduct product, int availableStock) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", product.getId());
        item.put("categoryId", product.getCategoryId());
        item.put("name", product.getName());
        item.put("category", product.getProductCategoryName() == null ? "未分类" : product.getProductCategoryName());
        item.put("productSn", product.getProductSn());
        item.put("price", product.getPrice());
        item.put("promotionPrice", product.getPromotionPrice());
        item.put("stock", availableStock);
        item.put("pic", product.getPic() == null ? "" : product.getPic());
        item.put("publishStatus", product.getPublishStatus());
        item.put("status", product.getPublishStatus() != null && product.getPublishStatus() == 1 ? "上架" : "下架");
        return item;
    }

    private void fillDoc(KnowledgeDoc doc, Map<String, Object> params) {
        if (params.containsKey("title")) doc.setTitle(RequestUtils.requiredString(params, "title"));
        if (params.containsKey("sourceType")) doc.setSourceType(RequestUtils.optionalString(params, "sourceType"));
        if (params.containsKey("content")) doc.setContent(RequestUtils.optionalString(params, "content"));
        if (params.containsKey("status")) doc.setStatus(RequestUtils.optionalString(params, "status"));
        if (params.containsKey("sourceSystem")) doc.setSourceSystem(RequestUtils.optionalString(params, "sourceSystem"));
        if (params.containsKey("sourceTrustScore")) {
            BigDecimal score = RequestUtils.optionalDecimal(params, "sourceTrustScore");
            if (score == null || score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("sourceTrustScore must be between 0 and 1");
            }
            doc.setSourceTrustScore(score);
        }
        if (params.containsKey("sourceUri")) doc.setSourceUri(RequestUtils.optionalString(params, "sourceUri"));
        if (params.containsKey("sourceHash")) doc.setSourceHash(RequestUtils.optionalString(params, "sourceHash"));
        if (params.containsKey("externalDocId")) doc.setExternalDocId(RequestUtils.optionalString(params, "externalDocId"));
        if (params.containsKey("visibilityScope")) doc.setVisibilityScope(RequestUtils.optionalString(params, "visibilityScope"));
        if (params.containsKey("tenantId")) {
            String tenantId = RequestUtils.optionalString(params, "tenantId");
            if (tenantId != null && !tenantId.isBlank() && !"default".equals(tenantId)) {
                throw new IllegalArgumentException("当前部署为单租户模式，拒绝非默认 tenantId");
            }
            doc.setTenantId("default");
        }
        if (params.containsKey("roleScope")) doc.setRoleScope(RequestUtils.optionalString(params, "roleScope"));
        if (params.containsKey("categoryIds")) doc.setCategoryIds(RequestUtils.optionalString(params, "categoryIds"));
        if (params.containsKey("activityId")) doc.setActivityId(RequestUtils.optionalString(params, "activityId"));
        if (params.containsKey("tags")) doc.setTags(RequestUtils.optionalString(params, "tags"));
    }

    private Map<String, Object> toKnowledgeIndexTaskMap(KnowledgeIndexTask task) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", task.getId());
        data.put("taskId", task.getTaskId() == null ? "" : task.getTaskId());
        data.put("batchId", task.getBatchId() == null ? "" : task.getBatchId());
        data.put("docId", task.getDocId());
        data.put("docVersionId", task.getDocVersionId());
        data.put("chunkId", task.getChunkId());
        data.put("taskType", task.getTaskType());
        data.put("status", task.getStatus());
        data.put("currentStep", task.getCurrentStep() == null ? "" : task.getCurrentStep());
        data.put("progressCurrent", task.getProgressCurrent());
        data.put("progressTotal", task.getProgressTotal());
        data.put("triggerType", task.getTriggerType());
        data.put("queueName", task.getQueueName());
        data.put("shardKey", task.getShardKey() == null ? "" : task.getShardKey());
        data.put("priority", task.getPriority());
        data.put("retryCount", task.getRetryCount());
        data.put("maxRetry", task.getMaxRetry());
        data.put("alertEnabled", task.getAlertEnabled());
        data.put("errorCode", task.getErrorCode() == null ? "" : task.getErrorCode());
        data.put("errorMessage", task.getErrorMessage() == null ? "" : task.getErrorMessage());
        data.put("deadLetterReason", task.getDeadLetterReason() == null ? "" : task.getDeadLetterReason());
        data.put("nextRetryAt", formatTime(task.getNextRetryAt()));
        data.put("lastHeartbeatAt", formatTime(task.getLastHeartbeatAt()));
        data.put("startedAt", formatTime(task.getStartedAt()));
        data.put("finishedAt", formatTime(task.getFinishedAt()));
        data.put("createdAt", formatTime(task.getCreatedAt()));
        data.put("updatedAt", formatTime(task.getUpdatedAt()));
        return data;
    }

    private Map<String, Object> toKnowledgeChunkMap(KnowledgeChunk chunk) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", chunk.getId());
        data.put("docId", chunk.getDocId());
        data.put("docVersion", chunk.getDocVersion());
        data.put("chunkKey", chunk.getChunkKey());
        data.put("chunkNo", chunk.getChunkNo());
        data.put("parentChunkId", chunk.getParentChunkId());
        data.put("prevChunkId", chunk.getPrevChunkId());
        data.put("nextChunkId", chunk.getNextChunkId());
        data.put("title", chunk.getTitle());
        data.put("sectionTitle", chunk.getSectionTitle() == null ? "" : chunk.getSectionTitle());
        data.put("sectionPath", chunk.getSectionPath() == null ? "[]" : chunk.getSectionPath());
        data.put("originalContent", chunk.getOriginalContent() == null ? "" : chunk.getOriginalContent());
        data.put("maskedContent", chunk.getMaskedContent() == null ? "" : chunk.getMaskedContent());
        data.put("indexContent", chunk.getIndexContent() == null ? "" : chunk.getIndexContent());
        data.put("snippet", chunk.getSnippet() == null ? "" : chunk.getSnippet());
        data.put("tokenCount", chunk.getTokenCount());
        data.put("contentHash", chunk.getContentHash());
        data.put("sourceType", chunk.getSourceType());
        data.put("sourceRef", chunk.getSourceRef());
        data.put("status", chunk.getStatus());
        data.put("visibilityScope", chunk.getVisibilityScope());
        data.put("tenantId", chunk.getTenantId());
        data.put("embeddingSyncStatus", chunk.getEmbeddingSyncStatus());
        data.put("indexVersion", chunk.getIndexVersion());
        data.put("chunkStrategyVersion", chunk.getChunkStrategyVersion());
        data.put("createdAt", formatTime(chunk.getCreatedAt()));
        data.put("updatedAt", formatTime(chunk.getUpdatedAt()));
        return data;
    }

    private Map<String, Object> toKnowledgeDocAuditLogMap(KnowledgeDocAuditLog log) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", log.getId());
        data.put("docId", log.getDocId());
        data.put("chunkId", log.getChunkId());
        data.put("action", log.getAction());
        data.put("operatorId", log.getOperatorId());
        data.put("operatorRole", log.getOperatorRole() == null ? "" : log.getOperatorRole());
        data.put("beforeSnapshotJson", log.getBeforeSnapshotJson() == null ? "" : log.getBeforeSnapshotJson());
        data.put("afterSnapshotJson", log.getAfterSnapshotJson() == null ? "" : log.getAfterSnapshotJson());
        data.put("reason", log.getReason() == null ? "" : log.getReason());
        data.put("createdAt", formatTime(log.getCreatedAt()));
        return data;
    }

    private Map<String, Object> toAdminOrder(OmsOrder order) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", order.getId());
        data.put("orderNo", order.getOrderSn());
        data.put("userId", order.getMemberId());
        data.put("memberUsername", order.getMemberUsername() == null ? "" : order.getMemberUsername());
        data.put("totalAmount", order.getTotalAmount());
        data.put("payAmount", order.getPayAmount());
        data.put("status", order.getStatus());
        data.put("receiverName", order.getReceiverName());
        data.put("receiverPhone", order.getReceiverPhone());
        data.put("deliveryCompany", order.getDeliveryCompany() == null ? "" : order.getDeliveryCompany());
        data.put("deliverySn", order.getDeliverySn() == null ? "" : order.getDeliverySn());
        data.put("createTime", formatTime(order.getCreateTime()));
        data.put("paymentTime", formatTime(order.getPaymentTime()));
        data.put("deliveryTime", formatTime(order.getDeliveryTime()));
        data.put("receiveTime", formatTime(order.getReceiveTime()));
        return data;
    }

    private String formatTime(java.time.LocalDateTime time) {
        return time == null ? "" : time.toString().replace("T", " ");
    }
}
