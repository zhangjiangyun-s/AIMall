package com.aimall.server.admin;

import com.aimall.server.common.ApiResponse;
import com.aimall.server.entity.KnowledgeTaskEvent;
import com.aimall.server.entity.KnowledgeChunk;
import com.aimall.server.entity.KnowledgeDoc;
import com.aimall.server.entity.KnowledgeDocVersion;
import com.aimall.server.entity.KnowledgeIndexTask;
import com.aimall.server.entity.KnowledgeQualityReport;
import com.aimall.server.entity.KnowledgeRetrievalTest;
import com.aimall.server.mapper.KnowledgeChunkMapper;
import com.aimall.server.mapper.KnowledgeDocMapper;
import com.aimall.server.mapper.KnowledgeDocVersionMapper;
import com.aimall.server.mapper.KnowledgeIndexTaskMapper;
import com.aimall.server.mapper.KnowledgeQualityReportMapper;
import com.aimall.server.mapper.KnowledgeRetrievalTestMapper;
import com.aimall.server.service.AdminKnowledgeUploadService;
import com.aimall.server.service.KnowledgePublicationService;
import com.aimall.server.service.KnowledgeChunkService;
import com.aimall.server.service.KnowledgeTaskEventService;
import com.aimall.server.security.AdminPermissions;
import com.aimall.server.security.RequireAdminPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.aimall.server.tenant.TenantPolicy;

@RestController
@RequestMapping("/api/admin/knowledge")
@RequireAdminPermission(AdminPermissions.KNOWLEDGE_VIEW)
public class AdminKnowledgeController {

    private final AdminKnowledgeUploadService uploadService;
    private final KnowledgeTaskEventService eventService;
    private final KnowledgePublicationService publicationService;
    private final KnowledgeChunkService chunkService;
    private final KnowledgeDocMapper docMapper;
    private final KnowledgeDocVersionMapper versionMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeIndexTaskMapper taskMapper;
    private final KnowledgeRetrievalTestMapper retrievalTestMapper;
    private final KnowledgeQualityReportMapper qualityReportMapper;
    private final TenantPolicy tenantPolicy;

    public AdminKnowledgeController(
            AdminKnowledgeUploadService uploadService,
            KnowledgeTaskEventService eventService,
            KnowledgePublicationService publicationService,
            KnowledgeChunkService chunkService,
            KnowledgeDocMapper docMapper,
            KnowledgeDocVersionMapper versionMapper,
            KnowledgeChunkMapper chunkMapper,
            KnowledgeIndexTaskMapper taskMapper,
            KnowledgeRetrievalTestMapper retrievalTestMapper,
            KnowledgeQualityReportMapper qualityReportMapper,
            TenantPolicy tenantPolicy
    ) {
        this.uploadService = uploadService;
        this.eventService = eventService;
        this.publicationService = publicationService;
        this.chunkService = chunkService;
        this.docMapper = docMapper;
        this.versionMapper = versionMapper;
        this.chunkMapper = chunkMapper;
        this.taskMapper = taskMapper;
        this.retrievalTestMapper = retrievalTestMapper;
        this.qualityReportMapper = qualityReportMapper;
        this.tenantPolicy = tenantPolicy;
    }

    @PostMapping("/upload/check")
    @RequireAdminPermission(AdminPermissions.KNOWLEDGE_EDIT)
    public ApiResponse<Map<String, Object>> checkUpload(
            @RequestParam("fileName") String fileName,
            @RequestParam(value = "fileSize", required = false) Long fileSize,
            @RequestParam(value = "sha256", required = false) String sha256
    ) {
        return ApiResponse.success(uploadService.checkUpload(fileName, fileSize, sha256));
    }

    @PostMapping(value = "/docs/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireAdminPermission(AdminPermissions.KNOWLEDGE_EDIT)
    public ApiResponse<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "visibilityScope", required = false) String visibilityScope,
            @RequestParam(value = "roleScope", required = false) String roleScope,
            @RequestParam(value = "categoryIds", required = false) String categoryIds,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "tags", required = false) String tags
    ) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("title", title);
        metadata.put("sourceType", sourceType);
        metadata.put("visibilityScope", visibilityScope);
        metadata.put("roleScope", roleScope);
        metadata.put("categoryIds", categoryIds);
        metadata.put("tenantId", tenantPolicy.resolve(tenantId));
        metadata.put("tags", tags);
        return ApiResponse.success(uploadService.upload(file, metadata));
    }

    @PostMapping(value = "/docs/upload/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireAdminPermission(AdminPermissions.KNOWLEDGE_EDIT)
    public ApiResponse<Map<String, Object>> uploadBatch(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "visibilityScope", required = false) String visibilityScope,
            @RequestParam(value = "roleScope", required = false) String roleScope,
            @RequestParam(value = "categoryIds", required = false) String categoryIds,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "tags", required = false) String tags
    ) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个知识库文档");
        }
        if (files.size() > 20) {
            throw new IllegalArgumentException("单次最多上传 20 个文档");
        }
        long totalSize = files.stream().mapToLong(MultipartFile::getSize).sum();
        if (totalSize > 200L * 1024 * 1024) {
            throw new IllegalArgumentException("批量文件总大小不能超过 200MB");
        }
        Map<String, String> sharedMetadata = new HashMap<>();
        sharedMetadata.put("title", files.size() == 1 ? title : null);
        sharedMetadata.put("sourceType", sourceType);
        sharedMetadata.put("visibilityScope", visibilityScope);
        sharedMetadata.put("roleScope", roleScope);
        sharedMetadata.put("categoryIds", categoryIds);
        sharedMetadata.put("tenantId", tenantPolicy.resolve(tenantId));
        sharedMetadata.put("tags", tags);

        List<Map<String, Object>> items = new ArrayList<>();
        int successCount = 0;
        for (MultipartFile file : files) {
            Map<String, Object> item = new HashMap<>();
            item.put("fileName", file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
            try {
                item.putAll(uploadService.upload(file, new HashMap<>(sharedMetadata)));
                item.put("ok", true);
                successCount += 1;
            } catch (Exception exception) {
                item.put("ok", false);
                item.put("error", exception.getMessage() == null ? "上传失败" : exception.getMessage());
            }
            items.add(item);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("total", files.size());
        result.put("successCount", successCount);
        result.put("failedCount", files.size() - successCount);
        result.put("items", items);
        return ApiResponse.success(result);
    }

    @PostMapping(value = "/docs/{docId}/versions/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireAdminPermission(AdminPermissions.KNOWLEDGE_EDIT)
    public ApiResponse<Map<String, Object>> uploadVersion(
            @PathVariable Long docId,
            @RequestParam("file") MultipartFile file
    ) {
        return ApiResponse.success(uploadService.uploadVersion(docId, file));
    }

    @GetMapping("/docs/{docId}/versions")
    public ApiResponse<List<Map<String, Object>>> listVersions(@PathVariable Long docId) {
        if (docMapper.selectById(docId) == null) throw new RuntimeException("知识库文档不存在");
        return ApiResponse.success(versionMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocVersion>()
                        .eq(KnowledgeDocVersion::getDocId, docId)
                        .orderByDesc(KnowledgeDocVersion::getVersionNo)
        ).stream().map(this::toVersionMap).toList());
    }

    @GetMapping("/tasks/{taskId}/events")
    public ApiResponse<List<Map<String, Object>>> listTaskEvents(@PathVariable String taskId) {
        return ApiResponse.success(eventService.listByTaskId(taskId).stream()
                .map(this::toTaskEventMap)
                .collect(Collectors.toList()));
    }

    @GetMapping(value = "/tasks/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTaskEvents(@PathVariable String taskId) {
        SseEmitter emitter = new SseEmitter(180000L);
        try {
            for (KnowledgeTaskEvent event : eventService.listByTaskId(taskId)) {
                emitter.send(SseEmitter.event()
                        .name("task_event")
                        .data(toTaskEventMap(event)));
            }
            emitter.send(SseEmitter.event()
                    .name("heartbeat")
                    .data(Map.of("time", LocalDateTime.now().toString())));
            emitter.complete();
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    @GetMapping("/docs/{docId}")
    public ApiResponse<Map<String, Object>> getDocumentDetail(@PathVariable Long docId) {
        KnowledgeDoc doc = docMapper.selectById(docId);
        if (doc == null) throw new RuntimeException("知识库文档不存在");
        List<KnowledgeDocVersion> versions = versionMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocVersion>()
                        .eq(KnowledgeDocVersion::getDocId, docId)
                        .orderByDesc(KnowledgeDocVersion::getVersionNo)
        );
        KnowledgeDocVersion latestVersion = versions.isEmpty() ? null : versions.get(0);
        KnowledgeDocVersion activeVersion = doc.getCurrentVersionId() == null ? null : versionMapper.selectById(doc.getCurrentVersionId());
        KnowledgeDocVersion version = latestVersion;
        if (latestVersion != null
                && ("ACTIVE".equals(latestVersion.getStatus())
                || "SUPERSEDED".equals(latestVersion.getStatus())
                || "DISABLED".equals(latestVersion.getStatus()))) {
            version = activeVersion == null ? latestVersion : activeVersion;
        }
        final KnowledgeDocVersion selectedVersion = version;
        List<KnowledgeChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getDocVersionId, selectedVersion == null ? -1L : selectedVersion.getId())
                        .orderByAsc(KnowledgeChunk::getChunkNo)
        );
        List<KnowledgeRetrievalTest> tests = retrievalTestMapper.selectList(
                new LambdaQueryWrapper<KnowledgeRetrievalTest>()
                        .eq(KnowledgeRetrievalTest::getDocId, docId)
                        .eq(KnowledgeRetrievalTest::getDocVersionId, selectedVersion == null ? -1L : selectedVersion.getId())
                        .orderByAsc(KnowledgeRetrievalTest::getId)
        );
        KnowledgeQualityReport report = qualityReportMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeQualityReport>()
                        .eq(KnowledgeQualityReport::getDocId, docId)
                        .eq(KnowledgeQualityReport::getDocVersionId, selectedVersion == null ? -1L : selectedVersion.getId())
                        .orderByDesc(KnowledgeQualityReport::getId)
                        .last("LIMIT 1")
        );
        KnowledgeIndexTask task = taskMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeIndexTask>()
                        .eq(KnowledgeIndexTask::getDocId, docId)
                        .orderByDesc(KnowledgeIndexTask::getId)
                        .last("LIMIT 1")
        );
        Map<String, Object> data = new HashMap<>();
        data.put("document", toDocumentMap(doc));
        data.put("version", selectedVersion == null ? Map.of() : toVersionMap(selectedVersion));
        data.put("versions", versions.stream().map(this::toVersionMap).toList());
        data.put("activeVersionId", doc.getCurrentVersionId());
        data.put("chunks", chunks.stream().map(this::toChunkMap).toList());
        data.put("retrievalTests", tests);
        data.put("qualityReport", report == null ? Map.of() : report);
        data.put("task", task == null ? Map.of() : toTaskMap(task));
        data.put("events", task == null ? List.of() : eventService.listByTaskId(task.getTaskId()).stream().map(this::toTaskEventMap).toList());
        return ApiResponse.success(data);
    }

    @PostMapping("/docs/{docId}/publish")
    @RequireAdminPermission(AdminPermissions.KNOWLEDGE_PUBLISH)
    public ApiResponse<Map<String, Object>> publish(@PathVariable Long docId) {
        KnowledgeDoc doc = publicationService.publish(docId);
        return ApiResponse.success(Map.of("docId", doc.getId(), "status", doc.getStatus()));
    }

    @PostMapping("/docs/{docId}/versions/{versionId}/publish")
    @RequireAdminPermission(AdminPermissions.KNOWLEDGE_PUBLISH)
    public ApiResponse<Map<String, Object>> publishVersion(@PathVariable Long docId, @PathVariable Long versionId) {
        KnowledgeDoc doc = publicationService.publishVersion(docId, versionId);
        return ApiResponse.success(Map.of("docId", doc.getId(), "versionId", versionId, "status", doc.getStatus()));
    }

    @PostMapping("/docs/{docId}/versions/{versionId}/rollback")
    @RequireAdminPermission(AdminPermissions.KNOWLEDGE_PUBLISH)
    public ApiResponse<Map<String, Object>> rollbackVersion(@PathVariable Long docId, @PathVariable Long versionId) {
        KnowledgeDoc doc = publicationService.rollbackVersion(docId, versionId);
        return ApiResponse.success(Map.of("docId", doc.getId(), "versionId", versionId, "status", doc.getStatus()));
    }

    @PostMapping("/docs/{docId}/disable")
    @RequireAdminPermission(AdminPermissions.KNOWLEDGE_PUBLISH)
    public ApiResponse<Map<String, Object>> disable(@PathVariable Long docId) {
        KnowledgeDoc doc = publicationService.disable(docId);
        return ApiResponse.success(Map.of("docId", doc.getId(), "status", doc.getStatus()));
    }

    @PostMapping("/vector-deletions/{chunkId}/retry")
    @RequireAdminPermission(AdminPermissions.AI_RECOVERY)
    public ApiResponse<Map<String, Object>> retryVectorDeletion(@PathVariable Long chunkId) {
        KnowledgeChunk chunk = chunkService.retryDeadVectorDeletion(chunkId);
        return ApiResponse.success(Map.of(
                "chunkId", chunk.getId(),
                "embeddingSyncStatus", chunk.getEmbeddingSyncStatus()
        ));
    }

    private Map<String, Object> toTaskEventMap(KnowledgeTaskEvent event) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", event.getId());
        data.put("taskId", event.getTaskId());
        data.put("eventType", event.getEventType());
        data.put("title", event.getTitle());
        data.put("detail", event.getDetail() == null ? "" : event.getDetail());
        data.put("progressCurrent", event.getProgressCurrent());
        data.put("progressTotal", event.getProgressTotal());
        data.put("ok", event.getOk());
        data.put("errorCode", event.getErrorCode() == null ? "" : event.getErrorCode());
        data.put("suggestion", event.getSuggestion() == null ? "" : event.getSuggestion());
        data.put("createdAt", event.getCreatedAt() == null ? "" : event.getCreatedAt().toString().replace("T", " "));
        return data;
    }

    private Map<String, Object> toDocumentMap(KnowledgeDoc doc) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", doc.getId());
        data.put("title", doc.getTitle());
        data.put("sourceType", doc.getSourceType());
        data.put("sourceTrustScore", doc.getSourceTrustScore());
        data.put("status", doc.getStatus());
        data.put("version", doc.getVersion());
        data.put("currentVersionId", doc.getCurrentVersionId());
        data.put("visibilityScope", doc.getVisibilityScope());
        data.put("roleScope", doc.getRoleScope() == null ? "" : doc.getRoleScope());
        data.put("categoryIds", doc.getCategoryIds() == null ? "" : doc.getCategoryIds());
        data.put("tags", doc.getTags() == null ? "" : doc.getTags());
        data.put("updatedAt", formatTime(doc.getUpdatedAt()));
        return data;
    }

    private Map<String, Object> toVersionMap(KnowledgeDocVersion version) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", version.getId());
        data.put("versionNo", version.getVersionNo());
        data.put("fileName", version.getFileName());
        data.put("fileType", version.getFileType());
        data.put("fileSize", version.getFileSize());
        data.put("status", version.getStatus());
        data.put("pageCount", version.getPageCount());
        data.put("paragraphCount", version.getParagraphCount());
        data.put("tableCount", version.getTableCount());
        data.put("imageCount", version.getImageCount());
        data.put("piiCount", version.getPiiCount());
        data.put("promptRiskLevel", version.getPromptRiskLevel());
        data.put("qualityScore", version.getQualityScore());
        data.put("createdAt", formatTime(version.getCreatedAt()));
        data.put("updatedAt", formatTime(version.getUpdatedAt()));
        return data;
    }

    private Map<String, Object> toChunkMap(KnowledgeChunk chunk) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", chunk.getId());
        data.put("chunkNo", chunk.getChunkNo());
        data.put("chunkType", chunk.getChunkType());
        data.put("sectionTitle", chunk.getSectionTitle() == null ? "" : chunk.getSectionTitle());
        data.put("sectionPath", chunk.getSectionPath() == null ? "[]" : chunk.getSectionPath());
        data.put("snippet", chunk.getSnippet() == null ? "" : chunk.getSnippet());
        data.put("pageStart", chunk.getPageStart());
        data.put("pageEnd", chunk.getPageEnd());
        data.put("status", chunk.getStatus());
        data.put("embeddingSyncStatus", chunk.getEmbeddingSyncStatus());
        return data;
    }

    private Map<String, Object> toTaskMap(KnowledgeIndexTask task) {
        Map<String, Object> data = new HashMap<>();
        data.put("taskId", task.getTaskId());
        data.put("status", task.getStatus());
        data.put("currentStep", task.getCurrentStep());
        data.put("progressCurrent", task.getProgressCurrent());
        data.put("progressTotal", task.getProgressTotal());
        data.put("errorCode", task.getErrorCode() == null ? "" : task.getErrorCode());
        data.put("errorMessage", task.getErrorMessage() == null ? "" : task.getErrorMessage());
        return data;
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? "" : time.toString().replace("T", " ");
    }
}
