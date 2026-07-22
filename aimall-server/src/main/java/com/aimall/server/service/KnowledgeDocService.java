package com.aimall.server.service;

import com.aimall.server.entity.KnowledgeDoc;

import java.util.List;

public interface KnowledgeDocService {
    List<KnowledgeDoc> listAll();
    KnowledgeDoc create(KnowledgeDoc doc);
    KnowledgeDoc update(KnowledgeDoc doc);
    void delete(Long id);
    void rebuild();
}
