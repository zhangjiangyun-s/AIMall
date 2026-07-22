package com.aimall.server.service.impl;

import com.aimall.server.entity.KnowledgeChunk;
import com.aimall.server.entity.KnowledgeDoc;
import com.aimall.server.entity.KnowledgeDocVersion;
import com.aimall.server.entity.KnowledgeQualityReport;
import com.aimall.server.mapper.KnowledgeChunkMapper;
import com.aimall.server.mapper.KnowledgeDocMapper;
import com.aimall.server.mapper.KnowledgeDocVersionMapper;
import com.aimall.server.mapper.KnowledgeQualityReportMapper;
import com.aimall.server.mapper.KnowledgeIndexTaskMapper;
import com.aimall.server.service.KnowledgeDocAuditLogService;
import com.aimall.server.service.KnowledgePublicationService;
import com.aimall.server.outbox.OutboxEventType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgePublicationServiceImpl implements KnowledgePublicationService {

    private final KnowledgeDocMapper docMapper;
    private final KnowledgeDocVersionMapper versionMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeQualityReportMapper qualityReportMapper;
    private final KnowledgeDocAuditLogService auditLogService;
    private final KnowledgeIndexTaskMapper indexTaskMapper;
    private final RestTemplate restTemplate;
    private final TransactionTemplate transactionTemplate;
    private final String aiServiceBaseUrl;
    private final Path storageRoot;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OutboxEventService outboxEventService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private KnowledgeRetrievalEpochService retrievalEpochService;

    public KnowledgePublicationServiceImpl(
            KnowledgeDocMapper docMapper,
            KnowledgeDocVersionMapper versionMapper,
            KnowledgeChunkMapper chunkMapper,
            KnowledgeQualityReportMapper qualityReportMapper,
            KnowledgeDocAuditLogService auditLogService,
            KnowledgeIndexTaskMapper indexTaskMapper,
            RestTemplate restTemplate,
            PlatformTransactionManager transactionManager,
            @Value("${ai-service.base-url:http://localhost:8000}") String aiServiceBaseUrl,
            @Value("${aimall.knowledge.storage-root:storage/knowledge}") String storageRoot
    ) {
        this.docMapper = docMapper;
        this.versionMapper = versionMapper;
        this.chunkMapper = chunkMapper;
        this.qualityReportMapper = qualityReportMapper;
        this.auditLogService = auditLogService;
        this.indexTaskMapper = indexTaskMapper;
        this.restTemplate = restTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.aiServiceBaseUrl = aiServiceBaseUrl.replaceAll("/+$", "");
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    @Override
    public KnowledgeDoc publish(Long docId) {
        KnowledgeDoc doc = requireDoc(docId);
        KnowledgeDocVersion version = versionMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocVersion>()
                        .eq(KnowledgeDocVersion::getDocId, docId)
                        .eq(KnowledgeDocVersion::getStatus, "READY")
                        .orderByDesc(KnowledgeDocVersion::getVersionNo)
                        .last("LIMIT 1")
        );
        if (version == null) {
            version = requireCurrentVersion(doc);
        }
        return publishVersion(docId, version.getId());
    }

    @Override
    public KnowledgeDoc publishVersion(Long docId, Long versionId) {
        return activateVersionSafely(docId, versionId, "READY", "PUBLISH_VERSION");
    }

    @Override
    public KnowledgeDoc rollbackVersion(Long docId, Long versionId) {
        KnowledgeDocVersion version = requireVersion(docId, versionId);
        if (!("SUPERSEDED".equals(version.getStatus()) || "DISABLED".equals(version.getStatus()))) {
            throw new RuntimeException("只能回滚到已下线的历史版本，当前状态：" + version.getStatus());
        }
        return activateVersionSafely(docId, versionId, version.getStatus(), "ROLLBACK_VERSION");
    }

    private KnowledgeDoc activateVersionSafely(Long docId, Long versionId, String expectedStatus, String action) {
        PublicationPlan plan = transactionTemplate.execute(status -> prepareActivation(docId, versionId, expectedStatus));
        if (plan == null) {
            throw new RuntimeException("知识版本发布准备失败");
        }
        try {
            applyMilvusActivation(plan);
        } catch (MilvusActivationException exception) {
            if (exception.state() == ActivationState.NOT_ACTIVATED) {
                transactionTemplate.executeWithoutResult(status -> restorePreparedVersion(plan));
                throw exception;
            }
            if (exception.state() == ActivationState.UNKNOWN) {
                throw new RuntimeException("知识版本激活结果未知，已保留 PUBLISHING 等待状态核对", exception);
            }
            try {
                compensateMilvusActivation(plan);
                transactionTemplate.executeWithoutResult(status -> restorePreparedVersion(plan));
            } catch (Exception compensationException) {
                exception.addSuppressed(compensationException);
                throw new RuntimeException("知识版本发布失败且 Milvus 补偿未完成，已保留 PUBLISHING 等待恢复", exception);
            }
            throw exception;
        }
        KnowledgeDoc activated = transactionTemplate.execute(status -> finalizeActivation(plan, action));
        if (activated == null) {
            throw new RuntimeException("知识版本发布结果落库失败，恢复任务将自动重试");
        }
        return activated;
    }

    private PublicationPlan prepareActivation(Long docId, Long versionId, String expectedStatus) {
        KnowledgeDoc doc = requireDoc(docId);
        KnowledgeDocVersion version = requireVersion(docId, versionId);
        if (!expectedStatus.equals(version.getStatus())) {
            throw new RuntimeException("知识版本状态已变化，当前状态：" + version.getStatus());
        }
        long expectedChunkCount = validatePublishable(doc, version);
        long retrievalEpoch = nextRetrievalEpoch();
        version.setPublicationVersion("doc-" + docId + "-v" + version.getVersionNo());
        version.setRetrievalEpoch(retrievalEpoch);
        version.setStatus("PUBLISHING");
        version.setUpdatedAt(LocalDateTime.now());
        versionMapper.updateById(version);
        return new PublicationPlan(docId, versionId, doc.getCurrentVersionId(), expectedStatus, expectedChunkCount);
    }

    private long validatePublishable(KnowledgeDoc doc, KnowledgeDocVersion version) {
        KnowledgeQualityReport report = latestQualityReport(doc.getId(), version.getId());
        if (report == null) {
            throw new RuntimeException("文档尚未完成质量评分");
        }
        if ("D".equalsIgnoreCase(report.getGrade())) {
            throw new RuntimeException("质量等级为 D，禁止发布");
        }
        long chunkCount = chunkMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getDocVersionId, version.getId())
        );
        if (chunkCount == 0) {
            throw new RuntimeException("目标版本没有可发布的 chunk");
        }
        long missingVector = chunkMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getDocVersionId, version.getId())
                        .isNull(KnowledgeChunk::getEmbeddingId)
        );
        if (missingVector > 0) {
            throw new RuntimeException("仍有 " + missingVector + " 个 chunk 缺少向量，不能切换版本");
        }
        return chunkCount;
    }

    private void applyMilvusActivation(PublicationPlan plan) {
        try {
            updateMilvusVersionStatus(plan.docId(), plan.targetVersionId(), "ACTIVE", false, plan.expectedChunkCount());
        } catch (Exception exception) {
            ActivationState state = queryTargetActivationState(plan);
            if (state != ActivationState.ACTIVATED) {
                throw new MilvusActivationException(state, exception);
            }
        }
        try {
            if (plan.previousVersionId() != null && !plan.previousVersionId().equals(plan.targetVersionId())) {
                updateMilvusVersionStatus(
                        plan.docId(), plan.previousVersionId(), "SUPERSEDED", true,
                        countVersionChunks(plan.previousVersionId())
                );
            }
        } catch (Exception exception) {
            throw new MilvusActivationException(ActivationState.ACTIVATED, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private ActivationState queryTargetActivationState(PublicationPlan plan) {
        try {
            Map<String, Object> response = restTemplate.getForObject(
                    aiServiceBaseUrl + "/ai/vector/docs/" + plan.docId()
                            + "/versions/" + plan.targetVersionId() + "/status",
                    Map.class
            );
            if (response == null || !Integer.valueOf(0).equals(response.get("code"))) {
                return ActivationState.UNKNOWN;
            }
            Object data = response.get("data");
            if (!(data instanceof Map<?, ?> result)) {
                return ActivationState.UNKNOWN;
            }
            if (Boolean.FALSE.equals(result.get("found"))) {
                Object emptyCount = result.get("vectorCount");
                return emptyCount == null || (emptyCount instanceof Number && ((Number) emptyCount).longValue() == 0)
                        ? ActivationState.NOT_ACTIVATED : ActivationState.UNKNOWN;
            }
            Object vectorCount = result.get("vectorCount");
            if (!(vectorCount instanceof Number)
                    || ((Number) vectorCount).longValue() != plan.expectedChunkCount()) {
                return ActivationState.UNKNOWN;
            }
            if (Boolean.TRUE.equals(result.get("allActive"))) {
                return ActivationState.ACTIVATED;
            }
            if (Boolean.TRUE.equals(result.get("anyActive"))) {
                return ActivationState.UNKNOWN;
            }
            return Boolean.FALSE.equals(result.get("found")) || result.containsKey("statuses")
                    ? ActivationState.NOT_ACTIVATED : ActivationState.UNKNOWN;
        } catch (Exception ignored) {
            return ActivationState.UNKNOWN;
        }
    }

    private void compensateMilvusActivation(PublicationPlan plan) {
        updateMilvusVersionStatus(
                plan.docId(), plan.targetVersionId(), plan.originalTargetStatus(), true, plan.expectedChunkCount()
        );
        if (plan.previousVersionId() != null && !plan.previousVersionId().equals(plan.targetVersionId())) {
            updateMilvusVersionStatus(
                    plan.docId(), plan.previousVersionId(), "ACTIVE", false,
                    countVersionChunks(plan.previousVersionId())
            );
        }
    }

    private long countVersionChunks(Long versionId) {
        return chunkMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeChunk>().eq(KnowledgeChunk::getDocVersionId, versionId)
        );
    }

    private KnowledgeDoc finalizeActivation(PublicationPlan plan, String action) {
        KnowledgeDoc doc = requireDoc(plan.docId());
        KnowledgeDocVersion version = requireVersion(plan.docId(), plan.targetVersionId());
        if (!"PUBLISHING".equals(version.getStatus())) {
            if ("ACTIVE".equals(version.getStatus()) && version.getId().equals(doc.getCurrentVersionId())) {
                return doc;
            }
            throw new RuntimeException("知识版本不在发布中状态");
        }
        LocalDateTime now = LocalDateTime.now();
        List<KnowledgeChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunk>().eq(KnowledgeChunk::getDocId, doc.getId())
        );
        for (KnowledgeChunk chunk : chunks) {
            if (version.getId().equals(chunk.getDocVersionId())) {
                chunk.setStatus("ACTIVE");
                chunk.setEmbeddingSyncStatus("SYNCED");
                chunk.setPublicationVersion(version.getPublicationVersion());
                chunk.setRetrievalEpoch(version.getRetrievalEpoch());
            } else if (plan.previousVersionId() != null && plan.previousVersionId().equals(chunk.getDocVersionId())) {
                chunk.setStatus("SUPERSEDED");
                chunk.setEmbeddingSyncStatus("DELETED");
            } else {
                continue;
            }
            chunk.setUpdatedAt(now);
            chunkMapper.updateById(chunk);
        }
        version.setStatus("ACTIVE");
        version.setUpdatedAt(now);
        versionMapper.updateById(version);
        if (plan.previousVersionId() != null && !plan.previousVersionId().equals(version.getId())) {
            KnowledgeDocVersion previous = versionMapper.selectById(plan.previousVersionId());
            if (previous != null) {
                previous.setStatus("SUPERSEDED");
                previous.setUpdatedAt(now);
                versionMapper.updateById(previous);
            }
        }
        doc.setCurrentVersionId(version.getId());
        doc.setVersion(version.getVersionNo());
        doc.setStatus("ACTIVE");
        doc.setUpdatedAt(now);
        docMapper.updateById(doc);
        auditLogService.record(doc.getId(), null, action, Map.of("previousVersionId", plan.previousVersionId() == null ? "" : plan.previousVersionId()), doc, "admin switch knowledge version to V" + version.getVersionNo());
        if (outboxEventService != null) {
            outboxEventService.enqueue("KNOWLEDGE_DOC", String.valueOf(doc.getId()), version.getVersionNo(),
                    OutboxEventType.KNOWLEDGE_PUBLISHED,
                    "KNOWLEDGE_PUBLISHED:" + doc.getId() + ":" + version.getId(), 1,
                    Map.of("docId", doc.getId(), "docVersionId", version.getId(),
                            "versionNo", version.getVersionNo(), "chunkCount", plan.expectedChunkCount(),
                            "publicationVersion", version.getPublicationVersion(),
                            "retrievalEpoch", version.getRetrievalEpoch()));
        }
        return doc;
    }

    private void restorePreparedVersion(PublicationPlan plan) {
        KnowledgeDocVersion version = versionMapper.selectById(plan.targetVersionId());
        if (version != null && "PUBLISHING".equals(version.getStatus())) {
            version.setStatus(plan.originalTargetStatus());
            version.setUpdatedAt(LocalDateTime.now());
            versionMapper.updateById(version);
        }
    }

    @Override
    public KnowledgeDoc disable(Long docId) {
        transactionTemplate.executeWithoutResult(status -> {
            KnowledgeDoc doc = requireDoc(docId);
            if ("DISABLED".equals(doc.getStatus())) {
                return;
            }
            doc.setStatus("DISABLING");
            advanceCurrentVersionEpoch(doc);
            doc.setUpdatedAt(LocalDateTime.now());
            docMapper.updateById(doc);
        });
        updateMilvusStatus(docId, "DISABLED", true);
        KnowledgeDoc disabled = transactionTemplate.execute(status -> finalizeDisable(docId));
        if (disabled == null) {
            throw new RuntimeException("知识文档禁用结果落库失败，恢复任务将自动重试");
        }
        return disabled;
    }

    private KnowledgeDoc finalizeDisable(Long docId) {
        KnowledgeDoc doc = requireDoc(docId);
        if ("DISABLED".equals(doc.getStatus())) {
            return doc;
        }
        if (!"DISABLING".equals(doc.getStatus())) {
            throw new RuntimeException("知识文档不在禁用中状态");
        }
        LocalDateTime now = LocalDateTime.now();
        for (KnowledgeChunk chunk : chunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getDocId, docId)
                        .ne(KnowledgeChunk::getStatus, "SUPERSEDED")
        )) {
            chunk.setStatus("DISABLED");
            chunk.setUpdatedAt(now);
            chunkMapper.updateById(chunk);
        }
        if (doc.getCurrentVersionId() != null) {
            KnowledgeDocVersion version = versionMapper.selectById(doc.getCurrentVersionId());
            if (version != null) {
                version.setStatus("DISABLED");
                version.setUpdatedAt(now);
                versionMapper.updateById(version);
            }
        }
        doc.setStatus("DISABLED");
        doc.setUpdatedAt(now);
        docMapper.updateById(doc);
        auditLogService.record(docId, null, "DISABLE", null, doc, "admin disable knowledge document");
        return doc;
    }

    @Override
    public void delete(Long docId) {
        transactionTemplate.executeWithoutResult(status -> prepareDelete(docId));
        updateMilvusStatus(docId, "DELETED", true);
        transactionTemplate.executeWithoutResult(status -> finalizeDelete(docId));
        cleanupDeletedFiles(docId);
    }

    private void prepareDelete(Long docId) {
        KnowledgeDoc doc = requireDoc(docId);
        if ("DELETED".equals(doc.getStatus())) {
            return;
        }
        doc.setStatus("DELETING");
        advanceCurrentVersionEpoch(doc);
        doc.setUpdatedAt(LocalDateTime.now());
        docMapper.updateById(doc);
        indexTaskMapper.update(
                null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.aimall.server.entity.KnowledgeIndexTask>()
                        .eq(com.aimall.server.entity.KnowledgeIndexTask::getDocId, docId)
                        .in(com.aimall.server.entity.KnowledgeIndexTask::getStatus, "PENDING", "RUNNING", "RETRY_WAIT")
                        .set(com.aimall.server.entity.KnowledgeIndexTask::getStatus, "CANCELED")
                        .set(com.aimall.server.entity.KnowledgeIndexTask::getFinishedAt, LocalDateTime.now())
                        .set(com.aimall.server.entity.KnowledgeIndexTask::getUpdatedAt, LocalDateTime.now())
        );
    }

    private void finalizeDelete(Long docId) {
        KnowledgeDoc doc = requireDoc(docId);
        KnowledgeDocVersion publishedVersion = doc.getCurrentVersionId() == null
                ? null : versionMapper.selectById(doc.getCurrentVersionId());
        LocalDateTime now = LocalDateTime.now();
        for (KnowledgeChunk chunk : chunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunk>().eq(KnowledgeChunk::getDocId, docId)
        )) {
            chunk.setStatus("DELETED");
            chunk.setEmbeddingSyncStatus("DELETED");
            chunk.setUpdatedAt(now);
            chunkMapper.updateById(chunk);
        }
        for (KnowledgeDocVersion version : versionMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocVersion>().eq(KnowledgeDocVersion::getDocId, docId)
        )) {
            version.setStatus("DELETED");
            version.setUpdatedAt(now);
            versionMapper.updateById(version);
        }
        doc.setStatus("DELETED");
        doc.setCurrentVersionId(null);
        doc.setUpdatedAt(now);
        docMapper.updateById(doc);
        auditLogService.record(docId, null, "DELETE", null, doc, "admin tombstone knowledge document");
        if (outboxEventService != null) {
            outboxEventService.enqueue("KNOWLEDGE_DOC", String.valueOf(docId),
                    doc.getVersion() == null ? 0L : doc.getVersion().longValue(),
                    OutboxEventType.KNOWLEDGE_DELETED, "KNOWLEDGE_DELETED:" + docId, 1,
                    Map.of(
                            "docId", docId,
                            "docVersionId", publishedVersion == null ? 0L : publishedVersion.getId(),
                            "publicationVersion", publishedVersion == null
                                    || publishedVersion.getPublicationVersion() == null
                                    ? "" : publishedVersion.getPublicationVersion(),
                            "retrievalEpoch", publishedVersion == null
                                    || publishedVersion.getRetrievalEpoch() == null
                                    ? 0L : publishedVersion.getRetrievalEpoch()
                    ));
        }
    }

    @Scheduled(fixedDelayString = "${aimall.knowledge.lifecycle-recovery-ms:30000}")
    public void recoverIncompleteLifecycle() {
        for (KnowledgeDocVersion version : versionMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocVersion>().eq(KnowledgeDocVersion::getStatus, "PUBLISHING").last("LIMIT 20")
        )) {
            try {
                KnowledgeDoc doc = requireDoc(version.getDocId());
                PublicationPlan plan = new PublicationPlan(
                        doc.getId(), version.getId(), doc.getCurrentVersionId(), "READY",
                        countVersionChunks(version.getId())
                );
                applyMilvusActivation(plan);
                transactionTemplate.execute(status -> finalizeActivation(plan, "RECOVER_PUBLISH_VERSION"));
            } catch (Exception ignored) {
                // The durable PUBLISHING state remains available for the next recovery pass.
            }
        }
        for (KnowledgeDoc doc : docMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDoc>().in(KnowledgeDoc::getStatus, "DISABLING", "DELETING").last("LIMIT 20")
        )) {
            try {
                if ("DISABLING".equals(doc.getStatus())) {
                    updateMilvusStatus(doc.getId(), "DISABLED", true);
                    transactionTemplate.execute(status -> finalizeDisable(doc.getId()));
                } else {
                    updateMilvusStatus(doc.getId(), "DELETED", true);
                    transactionTemplate.executeWithoutResult(status -> finalizeDelete(doc.getId()));
                    cleanupDeletedFiles(doc.getId());
                }
            } catch (Exception ignored) {
                // Keep the intermediate state and retry later.
            }
        }
        for (KnowledgeDoc doc : docMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDoc>().eq(KnowledgeDoc::getStatus, "DELETED").last("LIMIT 20")
        )) {
            cleanupDeletedFiles(doc.getId());
        }
    }

    private void cleanupDeletedFiles(Long docId) {
        for (KnowledgeDocVersion version : versionMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocVersion>()
                        .eq(KnowledgeDocVersion::getDocId, docId)
                        .eq(KnowledgeDocVersion::getStatus, "DELETED")
        )) {
            boolean cleaned = deleteStoredPath(version.getStoragePath())
                    & deleteStoredPath(version.getParsedJsonPath())
                    & deleteStoredPath(version.getPreviewTextPath());
            if (cleaned) {
                version.setStoragePath(null);
                version.setParsedJsonPath(null);
                version.setPreviewTextPath(null);
                version.setUpdatedAt(LocalDateTime.now());
                versionMapper.updateById(version);
            }
        }
    }

    private boolean deleteStoredPath(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        try {
            Path path = Path.of(value).toAbsolutePath().normalize();
            if (!path.startsWith(storageRoot)) {
                return false;
            }
            Files.deleteIfExists(path);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private record PublicationPlan(
            Long docId,
            Long targetVersionId,
            Long previousVersionId,
            String originalTargetStatus,
            long expectedChunkCount
    ) {
    }

    private enum ActivationState {
        ACTIVATED,
        NOT_ACTIVATED,
        UNKNOWN
    }

    private static final class MilvusActivationException extends RuntimeException {
        private final ActivationState state;

        private MilvusActivationException(ActivationState state, Throwable cause) {
            super(cause.getMessage(), cause);
            this.state = state;
        }

        private ActivationState state() {
            return state;
        }
    }

    private KnowledgeDoc requireDoc(Long docId) {
        KnowledgeDoc doc = docMapper.selectById(docId);
        if (doc == null) throw new RuntimeException("知识库文档不存在");
        return doc;
    }

    private KnowledgeDocVersion requireCurrentVersion(KnowledgeDoc doc) {
        if (doc.getCurrentVersionId() == null) throw new RuntimeException("文档没有可发布版本");
        KnowledgeDocVersion version = versionMapper.selectById(doc.getCurrentVersionId());
        if (version == null) throw new RuntimeException("当前文档版本不存在");
        return version;
    }

    private KnowledgeDocVersion requireVersion(Long docId, Long versionId) {
        KnowledgeDocVersion version = versionMapper.selectById(versionId);
        if (version == null || !docId.equals(version.getDocId())) {
            throw new RuntimeException("知识库文档版本不存在");
        }
        return version;
    }

    private KnowledgeQualityReport latestQualityReport(Long docId, Long versionId) {
        return qualityReportMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeQualityReport>()
                        .eq(KnowledgeQualityReport::getDocId, docId)
                        .eq(KnowledgeQualityReport::getDocVersionId, versionId)
                        .orderByDesc(KnowledgeQualityReport::getId)
                        .last("LIMIT 1")
        );
    }

    @SuppressWarnings("unchecked")
    private void updateMilvusStatus(Long docId, String status, boolean deleted) {
        Map<String, Object> request = new HashMap<>();
        request.put("status", status);
        request.put("isDeleted", deleted);
        KnowledgeDoc doc = requireDoc(docId);
        if (doc.getCurrentVersionId() != null) {
            KnowledgeDocVersion version = versionMapper.selectById(doc.getCurrentVersionId());
            if (version != null) {
                request.put("publicationVersion", version.getPublicationVersion());
                request.put("retrievalEpoch", version.getRetrievalEpoch());
            }
        }
        Map<String, Object> response = restTemplate.postForObject(
                aiServiceBaseUrl + "/ai/vector/docs/" + docId + "/status",
                request,
                Map.class
        );
        if (response == null || !Integer.valueOf(0).equals(response.get("code"))) {
            throw new RuntimeException("Milvus 文档状态更新失败");
        }
    }

    @SuppressWarnings("unchecked")
    private void updateMilvusVersionStatus(
            Long docId,
            Long versionId,
            String status,
            boolean deleted,
            long expectedChunkCount
    ) {
        KnowledgeDocVersion version = versionMapper.selectById(versionId);
        Map<String, Object> request = new HashMap<>();
        request.put("status", status);
        request.put("isDeleted", deleted);
        if (version != null) {
            request.put("publicationVersion", version.getPublicationVersion());
            request.put("retrievalEpoch", version.getRetrievalEpoch());
        }
        Map<String, Object> response = restTemplate.postForObject(
                aiServiceBaseUrl + "/ai/vector/docs/" + docId + "/versions/" + versionId + "/status",
                request,
                Map.class
        );
        if (response == null || !Integer.valueOf(0).equals(response.get("code"))) {
            throw new RuntimeException("Milvus 文档版本状态更新失败");
        }
        Object data = response.get("data");
        Object updated = data instanceof Map<?, ?> result ? result.get("updated") : null;
        if (!(updated instanceof Number) || ((Number) updated).longValue() != expectedChunkCount) {
            throw new RuntimeException("Milvus 文档版本向量更新数量不一致，期望 "
                    + expectedChunkCount + "，实际 " + updated);
        }
    }

    private long nextRetrievalEpoch() {
        return retrievalEpochService == null ? System.currentTimeMillis() : retrievalEpochService.next();
    }

    private void advanceCurrentVersionEpoch(KnowledgeDoc doc) {
        if (doc.getCurrentVersionId() == null) return;
        KnowledgeDocVersion version = versionMapper.selectById(doc.getCurrentVersionId());
        if (version == null) return;
        version.setRetrievalEpoch(nextRetrievalEpoch());
        if (version.getPublicationVersion() == null || version.getPublicationVersion().isBlank()) {
            version.setPublicationVersion("doc-" + doc.getId() + "-v" + version.getVersionNo());
        }
        version.setUpdatedAt(LocalDateTime.now());
        versionMapper.updateById(version);
        for (KnowledgeChunk chunk : chunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getDocVersionId, version.getId())
        )) {
            chunk.setPublicationVersion(version.getPublicationVersion());
            chunk.setRetrievalEpoch(version.getRetrievalEpoch());
            chunk.setUpdatedAt(LocalDateTime.now());
            chunkMapper.updateById(chunk);
        }
    }
}
