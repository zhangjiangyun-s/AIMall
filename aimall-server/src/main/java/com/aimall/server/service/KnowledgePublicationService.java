package com.aimall.server.service;

import com.aimall.server.entity.KnowledgeDoc;

public interface KnowledgePublicationService {
    KnowledgeDoc publish(Long docId);

    KnowledgeDoc publishVersion(Long docId, Long versionId);

    KnowledgeDoc rollbackVersion(Long docId, Long versionId);

    KnowledgeDoc disable(Long docId);

    void delete(Long docId);
}
