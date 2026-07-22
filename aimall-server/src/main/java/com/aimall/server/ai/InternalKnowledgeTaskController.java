package com.aimall.server.ai;

import com.aimall.server.common.ApiResponse;
import com.aimall.server.common.KnowledgeUtcTime;
import com.aimall.server.ai.dto.*;
import com.aimall.server.entity.KnowledgeDoc;
import com.aimall.server.entity.KnowledgeDocVersion;
import com.aimall.server.entity.KnowledgeChunk;
import com.aimall.server.entity.KnowledgeIndexTask;
import com.aimall.server.entity.EmbeddingCache;
import com.aimall.server.entity.KnowledgeQualityReport;
import com.aimall.server.entity.KnowledgeRetrievalTest;
import com.aimall.server.mapper.EmbeddingCacheMapper;
import com.aimall.server.mapper.KnowledgeChunkMapper;
import com.aimall.server.mapper.KnowledgeDocMapper;
import com.aimall.server.mapper.KnowledgeDocVersionMapper;
import com.aimall.server.mapper.KnowledgeIndexTaskMapper;
import com.aimall.server.mapper.KnowledgeQualityReportMapper;
import com.aimall.server.mapper.KnowledgeRetrievalTestMapper;
import com.aimall.server.service.KnowledgeTaskEventService;
import com.aimall.server.service.KnowledgeTaskExecutionGuard;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.Valid;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/ai/knowledge/tasks")
public class InternalKnowledgeTaskController {

    private final KnowledgeIndexTaskMapper taskMapper;
    private final KnowledgeDocMapper docMapper;
    private final KnowledgeDocVersionMapper versionMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final EmbeddingCacheMapper embeddingCacheMapper;
    private final KnowledgeRetrievalTestMapper retrievalTestMapper;
    private final KnowledgeQualityReportMapper qualityReportMapper;
    private final KnowledgeTaskEventService eventService;
    private final KnowledgeTaskExecutionGuard executionGuard;

    public InternalKnowledgeTaskController(
            KnowledgeIndexTaskMapper taskMapper,
            KnowledgeDocMapper docMapper,
            KnowledgeDocVersionMapper versionMapper,
            KnowledgeChunkMapper chunkMapper,
            EmbeddingCacheMapper embeddingCacheMapper,
            KnowledgeRetrievalTestMapper retrievalTestMapper,
            KnowledgeQualityReportMapper qualityReportMapper,
            KnowledgeTaskEventService eventService,
            KnowledgeTaskExecutionGuard executionGuard
    ) {
        this.taskMapper = taskMapper;
        this.docMapper = docMapper;
        this.versionMapper = versionMapper;
        this.chunkMapper = chunkMapper;
        this.embeddingCacheMapper = embeddingCacheMapper;
        this.retrievalTestMapper = retrievalTestMapper;
        this.qualityReportMapper = qualityReportMapper;
        this.eventService = eventService;
        this.executionGuard = executionGuard;
    }

    @GetMapping("/{taskId}")
    public ApiResponse<Map<String, Object>> getTask(
            @PathVariable String taskId,
            @RequestParam("executionToken") String executionToken
    ) {
        KnowledgeIndexTask task = executionGuard.requireActive(taskId, executionToken);
        KnowledgeDoc doc = task.getDocId() == null ? null : docMapper.selectById(task.getDocId());
        KnowledgeDocVersion version = task.getDocVersionId() == null ? null : versionMapper.selectById(task.getDocVersionId());
        Map<String, Object> data = new HashMap<>();
        data.put("taskId", task.getTaskId());
        data.put("dbTaskId", task.getId());
        data.put("docId", task.getDocId());
        data.put("docVersionId", task.getDocVersionId());
        data.put("taskType", task.getTaskType());
        data.put("status", task.getStatus());
        data.put("executionToken", task.getExecutionToken());
        data.put("attemptNo", task.getAttemptNo());
        data.put("title", doc == null ? "" : doc.getTitle());
        data.put("sourceType", doc == null ? "" : doc.getSourceType());
        data.put("visibilityScope", doc == null ? "" : doc.getVisibilityScope());
        data.put("tenantId", doc == null ? "default" : doc.getTenantId());
        data.put("roleScope", doc == null ? "" : nullToEmpty(doc.getRoleScope()));
        data.put("categoryIds", doc == null ? "" : nullToEmpty(doc.getCategoryIds()));
        data.put("fileName", version == null ? "" : version.getFileName());
        data.put("fileType", version == null ? "" : version.getFileType());
        data.put("fileSize", version == null ? 0 : version.getFileSize());
        data.put("storagePath", version == null ? "" : version.getStoragePath());
        data.put("sourceHash", version == null ? "" : version.getSourceHash());
        return ApiResponse.success(data);
    }

    @GetMapping("/{taskId}/acceptance")
    public ApiResponse<Map<String, Object>> getTaskAcceptance(@PathVariable String taskId) {
        KnowledgeIndexTask task = taskMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeIndexTask>()
                        .eq(KnowledgeIndexTask::getTaskId, taskId)
                        .last("LIMIT 1")
        );
        if (task == null) {
            throw new RuntimeException("Knowledge task does not exist");
        }
        List<Map<String, Object>> chunks = task.getDocVersionId() == null
                ? List.of()
                : chunkMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeChunk>()
                                .eq(KnowledgeChunk::getDocVersionId, task.getDocVersionId())
                                .ne(KnowledgeChunk::getStatus, "SUPERSEDED")
                                .orderByAsc(KnowledgeChunk::getChunkNo)
                ).stream().map(chunk -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", chunk.getId());
                    item.put("status", chunk.getStatus());
                    item.put("embeddingId", nullToEmpty(chunk.getEmbeddingId()));
                    item.put("embeddingSyncStatus", nullToEmpty(chunk.getEmbeddingSyncStatus()));
                    item.put("vectorCollection", nullToEmpty(chunk.getVectorCollection()));
                    return item;
                }).toList();
        Map<String, Object> data = new HashMap<>();
        data.put("taskId", task.getTaskId());
        data.put("docId", task.getDocId());
        data.put("docVersionId", task.getDocVersionId());
        data.put("status", task.getStatus());
        data.put("attemptNo", task.getAttemptNo());
        data.put("currentStep", nullToEmpty(task.getCurrentStep()));
        data.put("errorCode", nullToEmpty(task.getErrorCode()));
        data.put("errorMessage", nullToEmpty(task.getErrorMessage()));
        data.put("chunks", chunks);
        return ApiResponse.success(data);
    }

    @PostMapping("/{taskId}/events")
    @Transactional
    public ApiResponse<Map<String, Object>> recordEvent(
            @PathVariable String taskId,
            @Valid @RequestBody KnowledgeTaskEventRequest body
    ) {
        KnowledgeIndexTask task = executionGuard.lockActive(taskId, body.executionToken());
        eventService.record(
                taskId,
                body.eventType(), body.title(), body.detail() == null ? "" : body.detail(),
                body.progressCurrent(), body.progressTotal(), body.ok() == null || body.ok(),
                body.errorCode(), body.suggestion()
        );
        if ("RUNNING".equals(task.getStatus())) {
            LocalDateTime now = LocalDateTime.now();
            task.setLastHeartbeatAt(now);
            task.setLockUntil(now.plusMinutes(10));
            task.setTimeoutAt(now.plusMinutes(30));
            task.setUpdatedAt(now);
            taskMapper.updateById(task);
        }
        return ApiResponse.success(Map.of("taskId", taskId));
    }

    @PostMapping("/{taskId}/status")
    @Transactional
    public ApiResponse<Map<String, Object>> updateStatus(
            @PathVariable String taskId,
            @Valid @RequestBody KnowledgeTaskStatusRequest body
    ) {
        KnowledgeIndexTask task = executionGuard.lockActive(taskId, body.getExecutionToken());
        String status = body.getStatus();
        validateExecutionTransition(task.getStatus(), status);
        task.setStatus(status);
        task.setCurrentStep(body.getCurrentStep() == null ? task.getCurrentStep() : body.getCurrentStep());
        task.setProgressCurrent(body.getProgressCurrent());
        task.setProgressTotal(body.getProgressTotal());
        if (body.isErrorCodePresent()) {
            task.setErrorCode(body.getErrorCode() == null ? "" : body.getErrorCode());
        } else if ("SUCCESS".equals(status) || "RUNNING".equals(status)) {
            task.setErrorCode("");
        }
        if (body.isErrorMessagePresent()) {
            task.setErrorMessage(body.getErrorMessage() == null ? "" : body.getErrorMessage());
        } else if ("SUCCESS".equals(status) || "RUNNING".equals(status)) {
            task.setErrorMessage("");
        }
        if ("RUNNING".equals(status) && task.getStartedAt() == null) {
            task.setStartedAt(LocalDateTime.now());
        }
        if ("RUNNING".equals(status)) {
            LocalDateTime now = LocalDateTime.now();
            task.setLockedBy("aimall-ai-service");
            task.setLastHeartbeatAt(now);
            task.setLockUntil(now.plusMinutes(10));
            task.setTimeoutAt(now.plusMinutes(30));
            task.setNextRetryAt(null);
        }
        if ("SUCCESS".equals(status) || "FAILED".equals(status) || "PARTIAL_FAILED".equals(status)) {
            task.setFinishedAt(LocalDateTime.now());
            task.setLockedBy(null);
            task.setLockUntil(null);
        }
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        return ApiResponse.success(Map.of("taskId", taskId, "status", task.getStatus()));
    }

    @PostMapping("/versions/{versionId}/parse-result")
    @Transactional
    public ApiResponse<Map<String, Object>> updateParseResult(
            @PathVariable Long versionId,
            @Valid @RequestBody KnowledgeParseResultRequest body
    ) {
        KnowledgeIndexTask task = executionGuard.lockActive(
                body.executionTaskId(), body.executionToken()
        );
        requireRunningExecution(task);
        if (!versionId.equals(task.getDocVersionId())) {
            throw new IllegalStateException("文档版本不属于当前知识任务执行");
        }
        KnowledgeDocVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new RuntimeException("知识库文档版本不存在");
        }
        version.setParsedJsonPath(body.parsedJsonPath() == null ? version.getParsedJsonPath() : body.parsedJsonPath());
        version.setPreviewTextPath(body.previewTextPath() == null ? version.getPreviewTextPath() : body.previewTextPath());
        version.setPageCount(body.pageCount() == null ? version.getPageCount() : body.pageCount());
        version.setParagraphCount(body.paragraphCount() == null ? version.getParagraphCount() : body.paragraphCount());
        version.setTableCount(body.tableCount() == null ? version.getTableCount() : body.tableCount());
        version.setImageCount(body.imageCount() == null ? version.getImageCount() : body.imageCount());
        version.setPiiCount(body.piiCount() == null ? version.getPiiCount() : body.piiCount());
        version.setPromptRiskLevel(body.promptRiskLevel() == null ? version.getPromptRiskLevel() : body.promptRiskLevel());
        version.setStatus(body.status());
        version.setUpdatedAt(LocalDateTime.now());
        versionMapper.updateById(version);
        return ApiResponse.success(Map.of("versionId", version.getId(), "status", version.getStatus()));
    }

    @PostMapping("/{taskId}/chunks")
    @Transactional
    public ApiResponse<Map<String, Object>> replaceChunks(
            @PathVariable String taskId,
            @Valid @RequestBody KnowledgeChunkReplaceRequest body
    ) {
        KnowledgeIndexTask task = executionGuard.lockActive(taskId, body.executionToken());
        requireRunningExecution(task);
        if (task.getDocId() == null || task.getDocVersionId() == null) {
            throw new RuntimeException("知识库任务缺少文档版本信息");
        }
        KnowledgeDoc doc = docMapper.selectById(task.getDocId());
        if (doc == null) {
            throw new RuntimeException("知识库文档不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        List<KnowledgeChunk> existingChunks = chunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getDocVersionId, task.getDocVersionId())
                        .ne(KnowledgeChunk::getStatus, "SUPERSEDED")
        );
        Map<String, KnowledgeChunk> existingByKey = existingChunks.stream()
                .collect(java.util.stream.Collectors.toMap(KnowledgeChunk::getChunkKey, chunk -> chunk, (left, right) -> left));

        String targetDocStatus = body.docStatus() == null ? "READY_TO_PUBLISH" : body.docStatus();
        boolean preservePublishedVersion = isPublishedVersion(doc, task.getDocVersionId());
        String chunkStatus = preservePublishedVersion
                ? "ACTIVE"
                : "REVIEW_REQUIRED".equals(targetDocStatus) ? "REVIEW_REQUIRED" : "INDEXING";
        int saved = 0;
        KnowledgeChunk previous = null;
        for (KnowledgeChunkPayload chunkPayload : body.chunks()) {
            KnowledgeChunk chunk = toKnowledgeChunk(
                    doc, task.getDocVersionId(), chunkPayload.toMap(), chunkStatus, now
            );
            KnowledgeChunk existing = existingByKey.remove(chunk.getChunkKey());
            if (existing != null) {
                chunk.setId(existing.getId());
                chunk.setCreatedAt(existing.getCreatedAt());
                chunk.setEmbeddingId(existing.getEmbeddingId());
                chunk.setEmbeddingModel(existing.getEmbeddingModel());
                chunk.setEmbeddingModelVersion(existing.getEmbeddingModelVersion());
                chunk.setEmbeddingSyncStatus(
                        chunk.getContentHash().equals(existing.getContentHash())
                                ? existing.getEmbeddingSyncStatus()
                                : "PENDING"
                );
                chunk.setVectorCollection(existing.getVectorCollection());
            }
            if (previous != null) {
                chunk.setPrevChunkId(previous.getId());
            }
            if (chunk.getId() == null) {
                chunkMapper.insert(chunk);
            } else {
                chunkMapper.updateById(chunk);
            }
            if (previous != null) {
                previous.setNextChunkId(chunk.getId());
                previous.setUpdatedAt(now);
                chunkMapper.updateById(previous);
            }
            previous = chunk;
            saved += 1;
        }
        for (KnowledgeChunk removed : existingByKey.values()) {
            removed.setStatus("SUPERSEDED");
            removed.setEmbeddingSyncStatus(
                    removed.getEmbeddingId() == null || removed.getEmbeddingId().isBlank()
                            ? "DELETED"
                            : "DELETE_PENDING"
            );
            removed.setUpdatedAt(now);
            chunkMapper.updateById(removed);
        }

        KnowledgeDocVersion version = versionMapper.selectById(task.getDocVersionId());
        if (version != null) {
            version.setPiiCount(body.piiCount() == null ? version.getPiiCount() : body.piiCount());
            version.setPromptRiskLevel(body.promptRiskLevel() == null ? version.getPromptRiskLevel() : body.promptRiskLevel());
            if (!preservePublishedVersion) {
                version.setStatus(body.versionStatus() == null ? version.getStatus() : body.versionStatus());
            }
            version.setUpdatedAt(now);
            versionMapper.updateById(version);
        }
        if (!preservePublishedVersion) {
            doc.setStatus(body.docStatus() == null ? doc.getStatus() : body.docStatus());
        }
        doc.setUpdatedAt(now);
        docMapper.updateById(doc);

        return ApiResponse.success(Map.of("taskId", taskId, "chunkCount", saved));
    }

    @GetMapping("/{taskId}/chunks")
    public ApiResponse<List<Map<String, Object>>> listTaskChunks(
            @PathVariable String taskId,
            @RequestParam("executionToken") String executionToken
    ) {
        KnowledgeIndexTask task = executionGuard.requireActive(taskId, executionToken);
        requireRunningExecution(task);
        if (task.getDocVersionId() == null) {
            return ApiResponse.success(List.of());
        }
        List<Map<String, Object>> data = chunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getDocVersionId, task.getDocVersionId())
                        .ne(KnowledgeChunk::getStatus, "SUPERSEDED")
                        .orderByAsc(KnowledgeChunk::getChunkNo)
        ).stream().map(this::toChunkMap).toList();
        return ApiResponse.success(data);
    }

    @GetMapping("/embedding-cache")
    public ApiResponse<Map<String, Object>> getEmbeddingCache(
            @org.springframework.web.bind.annotation.RequestParam("contentHash") String contentHash,
            @org.springframework.web.bind.annotation.RequestParam("embeddingModel") String embeddingModel,
            @org.springframework.web.bind.annotation.RequestParam(value = "retrievalEpoch", defaultValue = "0") Long retrievalEpoch
    ) {
        EmbeddingCache cache = embeddingCacheMapper.selectOne(
                new LambdaQueryWrapper<EmbeddingCache>()
                        .eq(EmbeddingCache::getContentHash, contentHash)
                        .eq(EmbeddingCache::getEmbeddingModel, embeddingModel)
                        .eq(EmbeddingCache::getRetrievalEpoch, retrievalEpoch)
                        .and(wrapper -> wrapper.isNull(EmbeddingCache::getExpiredAt)
                                .or().gt(EmbeddingCache::getExpiredAt, LocalDateTime.now()))
                        .last("LIMIT 1")
        );
        if (cache == null) {
            return ApiResponse.success(Map.of("hit", false));
        }
        cache.setHitCount((cache.getHitCount() == null ? 0 : cache.getHitCount()) + 1);
        embeddingCacheMapper.updateById(cache);
        return ApiResponse.success(Map.of(
                "hit", true,
                "embeddingId", cache.getEmbeddingId() == null ? "" : cache.getEmbeddingId(),
                "vectorDimension", cache.getVectorDimension() == null ? 0 : cache.getVectorDimension()
        ));
    }

    @PostMapping("/embedding-cache")
    public ApiResponse<Map<String, Object>> upsertEmbeddingCache(
            @Valid @RequestBody EmbeddingCacheUpsertRequest body
    ) {
        String contentHash = body.contentHash();
        String embeddingModel = body.embeddingModel();
        Long retrievalEpoch = body.retrievalEpoch();
        EmbeddingCache cache = embeddingCacheMapper.selectOne(
                new LambdaQueryWrapper<EmbeddingCache>()
                        .eq(EmbeddingCache::getContentHash, contentHash)
                        .eq(EmbeddingCache::getEmbeddingModel, embeddingModel)
                        .eq(EmbeddingCache::getRetrievalEpoch, retrievalEpoch)
                        .last("LIMIT 1")
        );
        if (cache == null) {
            cache = new EmbeddingCache();
            cache.setContentHash(contentHash);
            cache.setEmbeddingModel(embeddingModel);
            cache.setRetrievalEpoch(retrievalEpoch);
            cache.setHitCount(0);
            cache.setCreatedAt(LocalDateTime.now());
        }
        cache.setEmbeddingId(body.embeddingId());
        cache.setVectorDimension(body.vectorDimension());
        cache.setExpiredAt(null);
        if (cache.getId() == null) {
            embeddingCacheMapper.insert(cache);
        } else {
            embeddingCacheMapper.updateById(cache);
        }
        return ApiResponse.success(Map.of("cacheId", cache.getId()));
    }

    @PostMapping("/{taskId}/evaluation")
    @Transactional
    public ApiResponse<Map<String, Object>> saveEvaluation(
            @PathVariable String taskId,
            @Valid @RequestBody KnowledgeEvaluationRequest body
    ) {
        KnowledgeIndexTask task = executionGuard.lockActive(taskId, body.executionToken());
        requireRunningExecution(task);
        if (task.getDocId() == null || task.getDocVersionId() == null) {
            throw new RuntimeException("知识库任务缺少文档版本信息");
        }
        retrievalTestMapper.delete(
                new LambdaQueryWrapper<KnowledgeRetrievalTest>()
                        .eq(KnowledgeRetrievalTest::getDocVersionId, task.getDocVersionId())
        );
        int passed = 0;
        for (KnowledgeEvaluationRequest.RetrievalTest item : body.retrievalTests()) {
            KnowledgeRetrievalTest test = new KnowledgeRetrievalTest();
            test.setDocId(task.getDocId());
            test.setDocVersionId(task.getDocVersionId());
            test.setTestQuery(item.testQuery());
            test.setExpectedDocId(task.getDocId());
            test.setHitDocId(item.hitDocId());
            test.setHitChunkId(item.hitChunkId());
            test.setTopScore(item.topScore() == null ? BigDecimal.ZERO : item.topScore());
            test.setPassed(item.passed());
            test.setDetail(item.detail() == null ? "" : item.detail());
            test.setCreatedAt(LocalDateTime.now());
            retrievalTestMapper.insert(test);
            if (Boolean.TRUE.equals(test.getPassed())) passed += 1;
        }

        KnowledgeEvaluationRequest.QualityReport qualityData = body.qualityReport();
        qualityReportMapper.delete(
                new LambdaQueryWrapper<KnowledgeQualityReport>()
                        .eq(KnowledgeQualityReport::getDocVersionId, task.getDocVersionId())
        );
        KnowledgeQualityReport report = new KnowledgeQualityReport();
        report.setDocId(task.getDocId());
        report.setDocVersionId(task.getDocVersionId());
        report.setParseScore(defaultDecimal(qualityData.parseScore()));
        report.setChunkScore(defaultDecimal(qualityData.chunkScore()));
        report.setPiiScore(defaultDecimal(qualityData.piiScore()));
        report.setPromptRiskScore(defaultDecimal(qualityData.promptRiskScore()));
        report.setRetrievalScore(defaultDecimal(qualityData.retrievalScore()));
        report.setSyncScore(defaultDecimal(qualityData.syncScore()));
        report.setTotalScore(defaultDecimal(qualityData.totalScore()));
        report.setGrade(qualityData.grade() == null ? "D" : qualityData.grade());
        report.setDetail(qualityData.detail() == null ? "" : qualityData.detail());
        report.setCreatedAt(LocalDateTime.now());
        qualityReportMapper.insert(report);

        KnowledgeDocVersion version = versionMapper.selectById(task.getDocVersionId());
        String recommendedStatus = body.recommendedStatus();
        if (version != null) {
            version.setQualityScore(report.getTotalScore());
            version.setStatus("READY_TO_PUBLISH".equals(recommendedStatus) ? "READY" : "REVIEW_REQUIRED");
            version.setUpdatedAt(LocalDateTime.now());
            versionMapper.updateById(version);
        }
        KnowledgeDoc doc = docMapper.selectById(task.getDocId());
        boolean preservePublishedVersion = doc != null && isPublishedVersion(doc, task.getDocVersionId());
        if (version != null && preservePublishedVersion) {
            version.setStatus("ACTIVE");
            versionMapper.updateById(version);
        }
        if (doc != null && !preservePublishedVersion
                && (doc.getCurrentVersionId() == null || doc.getCurrentVersionId().equals(task.getDocVersionId()))) {
            doc.setStatus(recommendedStatus);
            doc.setUpdatedAt(LocalDateTime.now());
            docMapper.updateById(doc);
        }
        for (KnowledgeChunk chunk : chunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getDocVersionId, task.getDocVersionId())
                        .ne(KnowledgeChunk::getStatus, "SUPERSEDED")
        )) {
            chunk.setStatus(preservePublishedVersion ? "ACTIVE" : recommendedStatus);
            chunk.setUpdatedAt(LocalDateTime.now());
            chunkMapper.updateById(chunk);
        }
        return ApiResponse.success(Map.of(
                "taskId", taskId,
                "testCount", body.retrievalTests().size(),
                "passedCount", passed,
                "qualityReportId", report.getId(),
                "grade", report.getGrade()
        ));
    }

    private KnowledgeIndexTask requireTask(String taskId) {
        KnowledgeIndexTask task = taskMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeIndexTask>()
                        .eq(KnowledgeIndexTask::getTaskId, taskId)
                        .last("LIMIT 1")
        );
        if (task == null) {
            throw new RuntimeException("知识库任务不存在");
        }
        return task;
    }

    private String stringValue(Object value, String defaultValue) {
        return value == null ? defaultValue : value.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private Integer intValue(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        return Integer.parseInt(value.toString());
    }

    private Integer intValueOrDefault(Object value, Integer defaultValue) {
        Integer parsed = intValue(value);
        return parsed == null ? defaultValue : parsed;
    }

    private BigDecimal defaultDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private KnowledgeChunk toKnowledgeChunk(
            KnowledgeDoc doc,
            Long docVersionId,
            Map<String, Object> data,
            String status,
            LocalDateTime now
    ) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setDocId(doc.getId());
        chunk.setDocVersionId(docVersionId);
        chunk.setDocVersion(doc.getVersion());
        chunk.setChunkKey(requiredString(data, "chunkKey"));
        chunk.setChunkType(stringValue(data.get("chunkType"), "TEXT"));
        chunk.setChunkNo(intValueOrDefault(data.get("chunkNo"), 1));
        chunk.setTitle(stringValue(data.get("title"), doc.getTitle()));
        chunk.setSectionTitle(stringValue(data.get("sectionTitle"), null));
        chunk.setSectionPath(stringValue(data.get("sectionPath"), "[]"));
        chunk.setOriginalContent(requiredString(data, "originalContent"));
        chunk.setMaskedContent(requiredString(data, "maskedContent"));
        chunk.setIndexContent(requiredString(data, "indexContent"));
        chunk.setSnippet(stringValue(data.get("snippet"), ""));
        chunk.setTokenCount(intValueOrDefault(data.get("tokenCount"), 1));
        chunk.setContentHash(requiredString(data, "contentHash"));
        chunk.setChunkHash(stringValue(data.get("chunkHash"), chunk.getContentHash()));
        chunk.setPageStart(intValue(data.get("pageStart")));
        chunk.setPageEnd(intValue(data.get("pageEnd")));
        chunk.setSourceType(doc.getSourceType());
        chunk.setSourceRef(doc.getSourceType() + "#" + doc.getId() + "#chunk-" + chunk.getChunkNo());
        chunk.setStatus(status);
        chunk.setVisibilityScope(doc.getVisibilityScope() == null ? "PUBLIC_USER" : doc.getVisibilityScope());
        chunk.setTenantId(doc.getTenantId() == null ? "default" : doc.getTenantId());
        chunk.setRoleScope(doc.getRoleScope());
        chunk.setCategoryIds(doc.getCategoryIds());
        chunk.setActivityId(doc.getActivityId());
        chunk.setEffectiveTime(doc.getEffectiveTime());
        chunk.setExpireTime(doc.getExpireTime());
        chunk.setEmbeddingSyncStatus("PENDING");
        chunk.setIndexVersion("index-v1");
        chunk.setChunkStrategyVersion("chunk-upload-v1");
        chunk.setCreatedAt(now);
        chunk.setUpdatedAt(now);
        return chunk;
    }

    private Map<String, Object> toChunkMap(KnowledgeChunk chunk) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", chunk.getId());
        data.put("docId", chunk.getDocId());
        data.put("docVersionId", chunk.getDocVersionId());
        data.put("docVersion", chunk.getDocVersion());
        data.put("chunkKey", chunk.getChunkKey());
        data.put("chunkNo", chunk.getChunkNo());
        data.put("chunkType", chunk.getChunkType());
        data.put("sectionTitle", chunk.getSectionTitle() == null ? "" : chunk.getSectionTitle());
        data.put("sectionPath", chunk.getSectionPath() == null ? "[]" : chunk.getSectionPath());
        data.put("indexContent", chunk.getIndexContent() == null ? "" : chunk.getIndexContent());
        data.put("maskedContent", chunk.getMaskedContent() == null ? "" : chunk.getMaskedContent());
        data.put("tokenCount", chunk.getTokenCount() == null ? 0 : chunk.getTokenCount());
        data.put("contentHash", chunk.getContentHash());
        data.put("chunkHash", chunk.getChunkHash());
        data.put("pageStart", chunk.getPageStart());
        data.put("pageEnd", chunk.getPageEnd());
        data.put("sourceType", chunk.getSourceType());
        data.put("sourceRef", chunk.getSourceRef());
        data.put("status", chunk.getStatus());
        data.put("visibilityScope", chunk.getVisibilityScope());
        data.put("tenantId", chunk.getTenantId());
        data.put("roleScope", chunk.getRoleScope() == null ? "" : chunk.getRoleScope());
        data.put("categoryIds", chunk.getCategoryIds() == null ? "" : chunk.getCategoryIds());
        data.put("effectiveTime", KnowledgeUtcTime.format(chunk.getEffectiveTime()));
        data.put("expireTime", KnowledgeUtcTime.format(chunk.getExpireTime()));
        data.put("embeddingId", chunk.getEmbeddingId() == null ? "" : chunk.getEmbeddingId());
        data.put("embeddingModel", chunk.getEmbeddingModel() == null ? "" : chunk.getEmbeddingModel());
        data.put("embeddingModelVersion", chunk.getEmbeddingModelVersion() == null ? "" : chunk.getEmbeddingModelVersion());
        data.put("embeddingSyncStatus", chunk.getEmbeddingSyncStatus());
        data.put("vectorCollection", chunk.getVectorCollection() == null ? "" : chunk.getVectorCollection());
        data.put("indexVersion", chunk.getIndexVersion());
        data.put("publicationVersion", chunk.getPublicationVersion() == null ? "" : chunk.getPublicationVersion());
        data.put("retrievalEpoch", chunk.getRetrievalEpoch() == null ? 0L : chunk.getRetrievalEpoch());
        return data;
    }

    private String requiredString(Map<String, Object> data, String key) {
        String value = stringValue(data.get(key), null);
        if (value == null || value.isBlank()) {
            throw new RuntimeException("chunk 缺少字段：" + key);
        }
        return value;
    }

    private void requireRunningExecution(KnowledgeIndexTask task) {
        if (!"RUNNING".equals(task.getStatus())) {
            throw new IllegalStateException("知识任务尚未进入 RUNNING，拒绝业务数据写回");
        }
    }

    private void validateExecutionTransition(String currentStatus, String targetStatus) {
        boolean allowed = switch (currentStatus) {
            case "DISPATCHING" -> "RUNNING".equals(targetStatus) || "FAILED".equals(targetStatus);
            case "RUNNING" -> List.of("RUNNING", "SUCCESS", "FAILED", "PARTIAL_FAILED").contains(targetStatus);
            default -> false;
        };
        if (!allowed) {
            throw new IllegalStateException("非法知识任务执行状态迁移：" + currentStatus + " -> " + targetStatus);
        }
    }

    private boolean isPublishedVersion(KnowledgeDoc doc, Long versionId) {
        return doc != null
                && "ACTIVE".equals(doc.getStatus())
                && doc.getCurrentVersionId() != null
                && doc.getCurrentVersionId().equals(versionId);
    }
}
