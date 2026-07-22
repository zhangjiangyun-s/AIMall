package com.aimall.server.service;

import com.aimall.server.entity.KnowledgeChunk;

import java.util.List;

public interface KnowledgeChunkService {
    List<KnowledgeChunk> listActive();

    List<KnowledgeChunk> listForVectorSync(String syncStatus, int limit);

    List<KnowledgeChunk> claimVectorDeletions(int limit);

    List<KnowledgeChunk> listByDocId(Long docId);

    List<KnowledgeChunk> listActiveByDocId(Long docId);

    List<KnowledgeChunk> listByIds(List<Long> chunkIds);

    KnowledgeChunk create(KnowledgeChunk chunk);

    KnowledgeChunk update(KnowledgeChunk chunk);

    KnowledgeChunk updateEmbeddingStatus(
            Long chunkId,
            String embeddingId,
            String embeddingModel,
            String embeddingModelVersion,
            String embeddingSyncStatus,
            String vectorCollection
    );

    KnowledgeChunk completeVectorDeletion(Long chunkId, String claimToken);

    KnowledgeChunk failVectorDeletion(Long chunkId, String claimToken, String errorMessage);

    KnowledgeChunk retryDeadVectorDeletion(Long chunkId);

    void disableByDocId(Long docId);

    long countActive();

    long countByEmbeddingSyncStatus(String status);
}
