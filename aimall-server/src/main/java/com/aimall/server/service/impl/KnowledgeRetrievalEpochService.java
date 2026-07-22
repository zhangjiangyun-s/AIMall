package com.aimall.server.service.impl;

import com.aimall.server.mapper.KnowledgeRetrievalEpochMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeRetrievalEpochService {
    private final KnowledgeRetrievalEpochMapper mapper;

    public KnowledgeRetrievalEpochService(KnowledgeRetrievalEpochMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public long next() {
        Long current = mapper.lockCurrent();
        if (current == null || mapper.advance(current) != 1) {
            throw new IllegalStateException("knowledge retrieval epoch advance failed");
        }
        return current + 1;
    }

    public long current() {
        Long value = mapper.current();
        return value == null ? 0 : value;
    }
}
