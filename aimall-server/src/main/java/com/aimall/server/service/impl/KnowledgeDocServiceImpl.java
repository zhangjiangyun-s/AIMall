package com.aimall.server.service.impl;

import com.aimall.server.entity.KnowledgeDoc;
import com.aimall.server.mapper.KnowledgeDocMapper;
import com.aimall.server.service.KnowledgeDocService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class KnowledgeDocServiceImpl implements KnowledgeDocService {

    private final KnowledgeDocMapper docMapper;

    public KnowledgeDocServiceImpl(KnowledgeDocMapper docMapper) {
        this.docMapper = docMapper;
    }

    @Override
    public List<KnowledgeDoc> listAll() {
        return docMapper.selectList(new LambdaQueryWrapper<KnowledgeDoc>().orderByAsc(KnowledgeDoc::getId));
    }

    @Override
    public KnowledgeDoc create(KnowledgeDoc doc) {
        if (doc.getStatus() == null) doc.setStatus("ENABLED");
        if (doc.getSourceType() == null) doc.setSourceType("POLICY");
        if (doc.getVersion() == null) doc.setVersion(1);
        applyMetadataDefaults(doc);
        docMapper.insert(doc);
        return doc;
    }

    @Override
    public KnowledgeDoc update(KnowledgeDoc doc) {
        KnowledgeDoc existing = docMapper.selectById(doc.getId());
        if (existing == null) {
            throw new RuntimeException("知识文档不存在");
        }
        if (doc.getTitle() != null) existing.setTitle(doc.getTitle());
        if (doc.getSourceType() != null) existing.setSourceType(doc.getSourceType());
        if (doc.getContent() != null) existing.setContent(doc.getContent());
        if (doc.getStatus() != null) existing.setStatus(doc.getStatus());
        if (doc.getSourceSystem() != null) existing.setSourceSystem(doc.getSourceSystem());
        if (doc.getSourceTrustScore() != null) {
            validateSourceTrustScore(doc.getSourceTrustScore());
            existing.setSourceTrustScore(doc.getSourceTrustScore());
        }
        if (doc.getSourceUri() != null) existing.setSourceUri(doc.getSourceUri());
        if (doc.getSourceHash() != null) existing.setSourceHash(doc.getSourceHash());
        if (doc.getExternalDocId() != null) existing.setExternalDocId(doc.getExternalDocId());
        if (doc.getVisibilityScope() != null) existing.setVisibilityScope(doc.getVisibilityScope());
        if (doc.getTenantId() != null) existing.setTenantId(doc.getTenantId());
        if (doc.getRoleScope() != null) existing.setRoleScope(doc.getRoleScope());
        if (doc.getCategoryIds() != null) existing.setCategoryIds(doc.getCategoryIds());
        if (doc.getActivityId() != null) existing.setActivityId(doc.getActivityId());
        if (doc.getTags() != null) existing.setTags(doc.getTags());
        if (doc.getEffectiveTime() != null) existing.setEffectiveTime(doc.getEffectiveTime());
        if (doc.getExpireTime() != null) existing.setExpireTime(doc.getExpireTime());
        if (doc.getUpdatedBy() != null) existing.setUpdatedBy(doc.getUpdatedBy());
        existing.setVersion((existing.getVersion() == null ? 0 : existing.getVersion()) + 1);
        existing.setUpdatedAt(LocalDateTime.now());
        docMapper.updateById(existing);
        return existing;
    }

    @Override
    public void delete(Long id) {
        docMapper.deleteById(id);
    }

    @Override
    public void rebuild() {
        // AI knowledge indexing hook reserved here.
    }

    private void applyMetadataDefaults(KnowledgeDoc doc) {
        LocalDateTime now = LocalDateTime.now();
        if (doc.getSourceSystem() == null) doc.setSourceSystem("manual");
        if (doc.getSourceTrustScore() == null) doc.setSourceTrustScore(BigDecimal.valueOf(0.5));
        validateSourceTrustScore(doc.getSourceTrustScore());
        if (doc.getVisibilityScope() == null) doc.setVisibilityScope("PUBLIC_USER");
        if (doc.getTenantId() == null) doc.setTenantId("default");
        if (doc.getCreatedAt() == null) doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
    }

    private void validateSourceTrustScore(BigDecimal score) {
        if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("sourceTrustScore must be between 0 and 1");
        }
    }
}
