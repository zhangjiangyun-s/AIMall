package com.aimall.server.service.impl;

import com.aimall.server.entity.KnowledgeChunk;
import com.aimall.server.mapper.KnowledgeChunkMapper;
import com.aimall.server.service.KnowledgeChunkService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class KnowledgeChunkServiceImpl implements KnowledgeChunkService {

    private final KnowledgeChunkMapper chunkMapper;

    public KnowledgeChunkServiceImpl(KnowledgeChunkMapper chunkMapper) {
        this.chunkMapper = chunkMapper;
    }

    @Override
    public List<KnowledgeChunk> listActive() {
        return chunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getStatus, "ACTIVE")
                        .orderByAsc(KnowledgeChunk::getDocId)
                        .orderByAsc(KnowledgeChunk::getChunkNo)
                        .orderByAsc(KnowledgeChunk::getId)
        );
    }

    @Override
    public List<KnowledgeChunk> listForVectorSync(String syncStatus, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        LambdaQueryWrapper<KnowledgeChunk> wrapper = new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getStatus, "ACTIVE")
                .orderByAsc(KnowledgeChunk::getId)
                .last("LIMIT " + safeLimit);
        if (syncStatus != null && !syncStatus.isBlank()) {
            wrapper.eq(KnowledgeChunk::getEmbeddingSyncStatus, syncStatus);
        }
        return chunkMapper.selectList(wrapper);
    }

    @Override
    public List<KnowledgeChunk> claimVectorDeletions(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        LocalDateTime now = LocalDateTime.now();
        List<KnowledgeChunk> candidates = chunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .and(status -> status
                                .and(pending -> pending
                                        .eq(KnowledgeChunk::getEmbeddingSyncStatus, "DELETE_PENDING")
                                        .and(retry -> retry
                                                .isNull(KnowledgeChunk::getVectorDeleteNextRetryAt)
                                                .or()
                                                .le(KnowledgeChunk::getVectorDeleteNextRetryAt, now)))
                                .or(expired -> expired
                                        .eq(KnowledgeChunk::getEmbeddingSyncStatus, "DELETE_PROCESSING")
                                        .le(KnowledgeChunk::getVectorDeleteClaimUntil, now)))
                        .orderByAsc(KnowledgeChunk::getId)
                        .last("LIMIT " + Math.min(1000, safeLimit * 5))
        );
        return candidates.stream()
                .map(candidate -> claimVectorDeletion(candidate, now))
                .filter(candidate -> candidate != null)
                .limit(safeLimit)
                .toList();
    }

    @Override
    public List<KnowledgeChunk> listByDocId(Long docId) {
        return chunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getDocId, docId)
                        .orderByAsc(KnowledgeChunk::getChunkNo)
                        .orderByAsc(KnowledgeChunk::getId)
        );
    }

    @Override
    public List<KnowledgeChunk> listActiveByDocId(Long docId) {
        return chunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getDocId, docId)
                        .eq(KnowledgeChunk::getStatus, "ACTIVE")
                        .orderByAsc(KnowledgeChunk::getChunkNo)
                        .orderByAsc(KnowledgeChunk::getId)
        );
    }

    @Override
    public List<KnowledgeChunk> listByIds(List<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        return chunkMapper.selectBatchIds(chunkIds);
    }

    @Override
    public KnowledgeChunk create(KnowledgeChunk chunk) {
        applyDefaults(chunk);
        chunkMapper.insert(chunk);
        return chunk;
    }

    @Override
    public KnowledgeChunk update(KnowledgeChunk chunk) {
        KnowledgeChunk existing = chunkMapper.selectById(chunk.getId());
        if (existing == null) {
            throw new RuntimeException("Knowledge chunk does not exist");
        }
        copyMutableFields(chunk, existing);
        existing.setUpdatedAt(LocalDateTime.now());
        chunkMapper.updateById(existing);
        return existing;
    }

    @Override
    public KnowledgeChunk updateEmbeddingStatus(
            Long chunkId,
            String embeddingId,
            String embeddingModel,
            String embeddingModelVersion,
            String embeddingSyncStatus,
            String vectorCollection
    ) {
        KnowledgeChunk existing = chunkMapper.selectById(chunkId);
        if (existing == null) {
            throw new RuntimeException("Knowledge chunk does not exist");
        }
        existing.setEmbeddingId(embeddingId);
        existing.setEmbeddingModel(embeddingModel);
        existing.setEmbeddingModelVersion(embeddingModelVersion);
        existing.setEmbeddingSyncStatus(embeddingSyncStatus);
        existing.setVectorCollection(vectorCollection);
        existing.setUpdatedAt(LocalDateTime.now());
        chunkMapper.updateById(existing);
        return existing;
    }

    @Override
    public KnowledgeChunk completeVectorDeletion(Long chunkId, String claimToken) {
        if (claimToken == null || claimToken.isBlank()) {
            throw new IllegalArgumentException("Vector deletion claim token is required");
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = chunkMapper.update(
                null,
                new LambdaUpdateWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getId, chunkId)
                        .eq(KnowledgeChunk::getEmbeddingSyncStatus, "DELETE_PROCESSING")
                        .eq(KnowledgeChunk::getVectorDeleteClaimToken, claimToken)
                        .set(KnowledgeChunk::getEmbeddingSyncStatus, "DELETED")
                        .set(KnowledgeChunk::getStatus, "DISABLED")
                        .set(KnowledgeChunk::getVectorDeleteClaimToken, null)
                        .set(KnowledgeChunk::getVectorDeleteClaimUntil, null)
                        .set(KnowledgeChunk::getVectorDeleteNextRetryAt, null)
                        .set(KnowledgeChunk::getVectorDeleteLastError, null)
                        .set(KnowledgeChunk::getUpdatedAt, now)
        );
        if (updated != 1) {
            KnowledgeChunk existing = chunkMapper.selectById(chunkId);
            if (existing != null && "DELETED".equals(existing.getEmbeddingSyncStatus())) {
                return existing;
            }
            throw new RuntimeException("Knowledge chunk is not waiting for vector deletion");
        }
        return chunkMapper.selectById(chunkId);
    }

    @Override
    public KnowledgeChunk failVectorDeletion(Long chunkId, String claimToken, String errorMessage) {
        if (claimToken == null || claimToken.isBlank()) {
            throw new IllegalArgumentException("Vector deletion claim token is required");
        }
        String safeError = errorMessage == null || errorMessage.isBlank() ? "Unknown Milvus deletion failure" : errorMessage;
        if (chunkMapper.failVectorDeletion(chunkId, claimToken, safeError, 8) != 1) {
            throw new IllegalStateException("Vector deletion lease is no longer owned by this worker");
        }
        return chunkMapper.selectById(chunkId);
    }

    @Override
    public KnowledgeChunk retryDeadVectorDeletion(Long chunkId) {
        LocalDateTime now = LocalDateTime.now();
        int updated = chunkMapper.update(
                null,
                new LambdaUpdateWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getId, chunkId)
                        .eq(KnowledgeChunk::getEmbeddingSyncStatus, "DELETE_DEAD")
                        .set(KnowledgeChunk::getEmbeddingSyncStatus, "DELETE_PENDING")
                        .set(KnowledgeChunk::getVectorDeleteRetryCount, 0)
                        .set(KnowledgeChunk::getVectorDeleteNextRetryAt, now)
                        .set(KnowledgeChunk::getVectorDeleteClaimToken, null)
                        .set(KnowledgeChunk::getVectorDeleteClaimUntil, null)
                        .set(KnowledgeChunk::getVectorDeleteLastError, null)
                        .set(KnowledgeChunk::getUpdatedAt, now)
        );
        if (updated != 1) {
            throw new IllegalStateException("Knowledge chunk is not in vector deletion dead letter");
        }
        return chunkMapper.selectById(chunkId);
    }

    private KnowledgeChunk claimVectorDeletion(KnowledgeChunk candidate, LocalDateTime now) {
        String previousStatus = candidate.getEmbeddingSyncStatus();
        String claimToken = UUID.randomUUID().toString().replace("-", "");
        LambdaUpdateWrapper<KnowledgeChunk> update = new LambdaUpdateWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getId, candidate.getId())
                .eq(KnowledgeChunk::getEmbeddingSyncStatus, previousStatus)
                .set(KnowledgeChunk::getEmbeddingSyncStatus, "DELETE_PROCESSING")
                .set(KnowledgeChunk::getVectorDeleteClaimToken, claimToken)
                .set(KnowledgeChunk::getVectorDeleteClaimUntil, now.plusMinutes(2))
                .set(KnowledgeChunk::getUpdatedAt, now);
        if ("DELETE_PENDING".equals(previousStatus)) {
            update.and(retry -> retry
                    .isNull(KnowledgeChunk::getVectorDeleteNextRetryAt)
                    .or()
                    .le(KnowledgeChunk::getVectorDeleteNextRetryAt, now));
        } else {
            update.le(KnowledgeChunk::getVectorDeleteClaimUntil, now);
        }
        if (chunkMapper.update(null, update) != 1) {
            return null;
        }
        candidate.setEmbeddingSyncStatus("DELETE_PROCESSING");
        candidate.setVectorDeleteClaimToken(claimToken);
        candidate.setVectorDeleteClaimUntil(now.plusMinutes(2));
        return candidate;
    }

    @Override
    public void disableByDocId(Long docId) {
        List<KnowledgeChunk> chunks = listActiveByDocId(docId);
        LocalDateTime now = LocalDateTime.now();
        for (KnowledgeChunk chunk : chunks) {
            chunk.setStatus("DISABLED");
            chunk.setUpdatedAt(now);
            chunkMapper.updateById(chunk);
        }
    }

    @Override
    public long countActive() {
        return chunkMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getStatus, "ACTIVE")
        );
    }

    @Override
    public long countByEmbeddingSyncStatus(String status) {
        return chunkMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getEmbeddingSyncStatus, status)
        );
    }

    private void applyDefaults(KnowledgeChunk chunk) {
        LocalDateTime now = LocalDateTime.now();
        if (chunk.getStatus() == null) chunk.setStatus("ACTIVE");
        if (chunk.getVisibilityScope() == null) chunk.setVisibilityScope("PUBLIC_USER");
        if (chunk.getTenantId() == null) chunk.setTenantId("default");
        if (chunk.getEmbeddingSyncStatus() == null) chunk.setEmbeddingSyncStatus("PENDING");
        if (chunk.getChunkStrategyVersion() == null) chunk.setChunkStrategyVersion("chunk-v1");
        if (chunk.getIndexVersion() == null) chunk.setIndexVersion("index-v1");
        if (chunk.getCreatedAt() == null) chunk.setCreatedAt(now);
        chunk.setUpdatedAt(now);
    }

    private void copyMutableFields(KnowledgeChunk source, KnowledgeChunk target) {
        if (source.getParentChunkId() != null) target.setParentChunkId(source.getParentChunkId());
        if (source.getPrevChunkId() != null) target.setPrevChunkId(source.getPrevChunkId());
        if (source.getNextChunkId() != null) target.setNextChunkId(source.getNextChunkId());
        if (source.getTitle() != null) target.setTitle(source.getTitle());
        if (source.getSectionTitle() != null) target.setSectionTitle(source.getSectionTitle());
        if (source.getSectionPath() != null) target.setSectionPath(source.getSectionPath());
        if (source.getOriginalContent() != null) target.setOriginalContent(source.getOriginalContent());
        if (source.getMaskedContent() != null) target.setMaskedContent(source.getMaskedContent());
        if (source.getIndexContent() != null) target.setIndexContent(source.getIndexContent());
        if (source.getSnippet() != null) target.setSnippet(source.getSnippet());
        if (source.getTokenCount() != null) target.setTokenCount(source.getTokenCount());
        if (source.getStatus() != null) target.setStatus(source.getStatus());
        if (source.getVisibilityScope() != null) target.setVisibilityScope(source.getVisibilityScope());
        if (source.getRoleScope() != null) target.setRoleScope(source.getRoleScope());
        if (source.getCategoryIds() != null) target.setCategoryIds(source.getCategoryIds());
        if (source.getActivityId() != null) target.setActivityId(source.getActivityId());
        if (source.getEffectiveTime() != null) target.setEffectiveTime(source.getEffectiveTime());
        if (source.getExpireTime() != null) target.setExpireTime(source.getExpireTime());
        if (source.getKeywordIndexVersion() != null) target.setKeywordIndexVersion(source.getKeywordIndexVersion());
        if (source.getEmbeddingId() != null) target.setEmbeddingId(source.getEmbeddingId());
        if (source.getEmbeddingModel() != null) target.setEmbeddingModel(source.getEmbeddingModel());
        if (source.getEmbeddingModelVersion() != null) target.setEmbeddingModelVersion(source.getEmbeddingModelVersion());
        if (source.getEmbeddingSyncStatus() != null) target.setEmbeddingSyncStatus(source.getEmbeddingSyncStatus());
        if (source.getVectorCollection() != null) target.setVectorCollection(source.getVectorCollection());
        if (source.getIndexVersion() != null) target.setIndexVersion(source.getIndexVersion());
        if (source.getChunkStrategyVersion() != null) target.setChunkStrategyVersion(source.getChunkStrategyVersion());
    }
}
