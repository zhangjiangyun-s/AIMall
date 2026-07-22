package com.aimall.server.service.impl;

import com.aimall.server.entity.KnowledgeDoc;
import com.aimall.server.entity.KnowledgeDocVersion;
import com.aimall.server.entity.KnowledgeChunk;
import com.aimall.server.entity.KnowledgeQualityReport;
import com.aimall.server.outbox.OutboxEventType;
import com.aimall.server.mapper.KnowledgeChunkMapper;
import com.aimall.server.mapper.KnowledgeDocMapper;
import com.aimall.server.mapper.KnowledgeDocVersionMapper;
import com.aimall.server.mapper.KnowledgeIndexTaskMapper;
import com.aimall.server.mapper.KnowledgeQualityReportMapper;
import com.aimall.server.service.KnowledgeDocAuditLogService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgePublicationServiceImplTest {

    @Test
    void deletionAdvancesChunkEpochAndPublishesInvalidationMetadata() {
        KnowledgeDocMapper docMapper = mock(KnowledgeDocMapper.class);
        KnowledgeDocVersionMapper versionMapper = mock(KnowledgeDocVersionMapper.class);
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeIndexTaskMapper taskMapper = mock(KnowledgeIndexTaskMapper.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setId(10L);
        doc.setVersion(2);
        doc.setCurrentVersionId(2L);
        doc.setStatus("ACTIVE");
        KnowledgeDocVersion version = new KnowledgeDocVersion();
        version.setId(2L);
        version.setDocId(10L);
        version.setVersionNo(2);
        version.setStatus("ACTIVE");
        version.setPublicationVersion("doc-10-v2");
        version.setRetrievalEpoch(7L);
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(20L);
        chunk.setDocId(10L);
        chunk.setDocVersionId(2L);
        chunk.setStatus("ACTIVE");
        when(docMapper.selectById(10L)).thenReturn(doc);
        when(versionMapper.selectById(2L)).thenReturn(version);
        when(versionMapper.selectList(any())).thenReturn(List.of(version));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));
        when(restTemplate.postForObject(any(String.class), any(), eq(Map.class)))
                .thenReturn(Map.of("code", 0));
        KnowledgePublicationServiceImpl service = new KnowledgePublicationServiceImpl(
                docMapper, versionMapper, chunkMapper, mock(KnowledgeQualityReportMapper.class),
                mock(KnowledgeDocAuditLogService.class), taskMapper, restTemplate,
                new NoOpTransactionManager(), "http://localhost:8000", "storage/knowledge"
        );
        KnowledgeRetrievalEpochService epochService = mock(KnowledgeRetrievalEpochService.class);
        when(epochService.next()).thenReturn(8L);
        OutboxEventService outbox = mock(OutboxEventService.class);
        ReflectionTestUtils.setField(service, "retrievalEpochService", epochService);
        ReflectionTestUtils.setField(service, "outboxEventService", outbox);

        ReflectionTestUtils.invokeMethod(service, "advanceCurrentVersionEpoch", doc);
        ReflectionTestUtils.invokeMethod(service, "finalizeDelete", 10L);

        assertEquals(8L, version.getRetrievalEpoch());
        assertEquals(8L, chunk.getRetrievalEpoch());
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(outbox).enqueue(
                eq("KNOWLEDGE_DOC"), eq("10"), eq(2L), eq(OutboxEventType.KNOWLEDGE_DELETED),
                eq("KNOWLEDGE_DELETED:10"), eq(1), payload.capture()
        );
        assertEquals("doc-10-v2", payload.getValue().get("publicationVersion"));
        assertEquals(8L, payload.getValue().get("retrievalEpoch"));
    }

    @Test
    void publicationAssignsVersionAndEpochToMilvusSwitch() {
        TestFixture fixture = fixture();
        KnowledgeRetrievalEpochService epochService = mock(KnowledgeRetrievalEpochService.class);
        when(epochService.next()).thenReturn(77L);
        ReflectionTestUtils.setField(fixture.service, "retrievalEpochService", epochService);
        when(fixture.restTemplate.postForObject(any(String.class), any(), eq(Map.class)))
                .thenReturn(Map.of("code", 0, "data", Map.of("updated", 1)));

        fixture.service.publishVersion(10L, 2L);

        assertEquals("doc-10-v2", fixture.target.getPublicationVersion());
        assertEquals(77L, fixture.target.getRetrievalEpoch());
        ArgumentCaptor<Object> requestCaptor = ArgumentCaptor.forClass(Object.class);
        verify(fixture.restTemplate, atLeastOnce())
                .postForObject(any(String.class), requestCaptor.capture(), eq(Map.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> targetRequest = (Map<String, Object>) requestCaptor.getAllValues().get(0);
        assertEquals("doc-10-v2", targetRequest.get("publicationVersion"));
        assertEquals(77L, targetRequest.get("retrievalEpoch"));
    }

    @Test
    void unknownTargetActivationResultKeepsPublishingForRecovery() {
        KnowledgeDocMapper docMapper = mock(KnowledgeDocMapper.class);
        KnowledgeDocVersionMapper versionMapper = mock(KnowledgeDocVersionMapper.class);
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeQualityReportMapper qualityMapper = mock(KnowledgeQualityReportMapper.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setId(10L);
        doc.setCurrentVersionId(1L);
        KnowledgeDocVersion target = new KnowledgeDocVersion();
        target.setId(2L);
        target.setDocId(10L);
        target.setVersionNo(2);
        target.setStatus("READY");
        KnowledgeQualityReport report = new KnowledgeQualityReport();
        report.setGrade("A");
        when(docMapper.selectById(10L)).thenReturn(doc);
        when(versionMapper.selectById(2L)).thenReturn(target);
        when(qualityMapper.selectOne(any())).thenReturn(report);
        when(chunkMapper.selectCount(any())).thenReturn(1L, 0L, 1L, 1L);
        when(restTemplate.postForObject(any(String.class), any(), eq(Map.class))).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            throw new RuntimeException("milvus unavailable");
        });
        KnowledgePublicationServiceImpl service = new KnowledgePublicationServiceImpl(
                docMapper,
                versionMapper,
                chunkMapper,
                qualityMapper,
                mock(KnowledgeDocAuditLogService.class),
                mock(KnowledgeIndexTaskMapper.class),
                restTemplate,
                new NoOpTransactionManager(),
                "http://localhost:8000",
                "storage/knowledge"
        );

        assertThrows(RuntimeException.class, () -> service.publishVersion(10L, 2L));

        assertEquals("PUBLISHING", target.getStatus());
    }

    @Test
    void confirmedNotActivatedTargetRestoresReadyState() {
        TestFixture fixture = fixture();
        when(fixture.restTemplate.postForObject(any(String.class), any(), eq(Map.class)))
                .thenThrow(new RuntimeException("response failed"));
        when(fixture.restTemplate.getForObject(any(String.class), eq(Map.class)))
                .thenReturn(Map.of("code", 0, "data", Map.of("found", false, "statuses", java.util.List.of())));

        assertThrows(RuntimeException.class, () -> fixture.service.publishVersion(10L, 2L));

        assertEquals("READY", fixture.target.getStatus());
    }

    @Test
    void responseLossWithActiveTargetContinuesAndFinalizesPublication() {
        TestFixture fixture = fixture();
        when(fixture.restTemplate.postForObject(any(String.class), any(), eq(Map.class)))
                .thenThrow(new RuntimeException("response lost"))
                .thenReturn(Map.of("code", 0, "data", Map.of("updated", 1)));
        when(fixture.restTemplate.getForObject(any(String.class), eq(Map.class)))
                .thenReturn(Map.of("code", 0, "data", Map.of(
                        "found", true, "vectorCount", 1, "allActive", true,
                        "statuses", java.util.List.of("ACTIVE")
                )));

        KnowledgeDoc result = fixture.service.publishVersion(10L, 2L);

        assertEquals(2L, result.getCurrentVersionId());
        assertEquals("ACTIVE", fixture.target.getStatus());
    }

    @Test
    void partialTargetActivationNeverFinalizesPublication() {
        TestFixture fixture = fixture();
        when(fixture.restTemplate.postForObject(any(String.class), any(), eq(Map.class)))
                .thenThrow(new RuntimeException("partial update timeout"));
        when(fixture.restTemplate.getForObject(any(String.class), eq(Map.class)))
                .thenReturn(Map.of("code", 0, "data", Map.of(
                        "found", true, "vectorCount", 1, "anyActive", true,
                        "allActive", false, "statuses", java.util.List.of("ACTIVE", "READY")
                )));

        assertThrows(RuntimeException.class, () -> fixture.service.publishVersion(10L, 2L));

        assertEquals("PUBLISHING", fixture.target.getStatus());
    }

    @Test
    void secondStepFailureDeactivatesTargetAndReactivatesPreviousBeforeRestoringState() {
        KnowledgeDocMapper docMapper = mock(KnowledgeDocMapper.class);
        KnowledgeDocVersionMapper versionMapper = mock(KnowledgeDocVersionMapper.class);
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeQualityReportMapper qualityMapper = mock(KnowledgeQualityReportMapper.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setId(10L);
        doc.setCurrentVersionId(1L);
        KnowledgeDocVersion target = new KnowledgeDocVersion();
        target.setId(2L);
        target.setDocId(10L);
        target.setVersionNo(2);
        target.setStatus("READY");
        KnowledgeQualityReport report = new KnowledgeQualityReport();
        report.setGrade("A");
        when(docMapper.selectById(10L)).thenReturn(doc);
        when(versionMapper.selectById(2L)).thenReturn(target);
        when(qualityMapper.selectOne(any())).thenReturn(report);
        when(chunkMapper.selectCount(any())).thenReturn(1L, 0L, 1L, 1L);
        when(restTemplate.postForObject(any(String.class), any(), eq(Map.class)))
                .thenReturn(Map.of("code", 0, "data", Map.of("updated", 1)))
                .thenThrow(new RuntimeException("old version update failed"))
                .thenReturn(Map.of("code", 0, "data", Map.of("updated", 1)))
                .thenReturn(Map.of("code", 0, "data", Map.of("updated", 1)));
        KnowledgePublicationServiceImpl service = new KnowledgePublicationServiceImpl(
                docMapper, versionMapper, chunkMapper, qualityMapper,
                mock(KnowledgeDocAuditLogService.class), mock(KnowledgeIndexTaskMapper.class), restTemplate,
                new NoOpTransactionManager(), "http://localhost:8000", "storage/knowledge"
        );

        assertThrows(RuntimeException.class, () -> service.publishVersion(10L, 2L));

        assertEquals("READY", target.getStatus());
        org.mockito.Mockito.verify(restTemplate, org.mockito.Mockito.times(4))
                .postForObject(any(String.class), any(), eq(Map.class));
    }

    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    private TestFixture fixture() {
        KnowledgeDocMapper docMapper = mock(KnowledgeDocMapper.class);
        KnowledgeDocVersionMapper versionMapper = mock(KnowledgeDocVersionMapper.class);
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        KnowledgeQualityReportMapper qualityMapper = mock(KnowledgeQualityReportMapper.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setId(10L);
        doc.setCurrentVersionId(1L);
        KnowledgeDocVersion target = new KnowledgeDocVersion();
        target.setId(2L);
        target.setDocId(10L);
        target.setVersionNo(2);
        target.setStatus("READY");
        KnowledgeQualityReport report = new KnowledgeQualityReport();
        report.setGrade("A");
        when(docMapper.selectById(10L)).thenReturn(doc);
        when(versionMapper.selectById(2L)).thenReturn(target);
        when(qualityMapper.selectOne(any())).thenReturn(report);
        when(chunkMapper.selectCount(any())).thenReturn(1L, 0L, 1L, 1L);
        KnowledgePublicationServiceImpl service = new KnowledgePublicationServiceImpl(
                docMapper, versionMapper, chunkMapper, qualityMapper,
                mock(KnowledgeDocAuditLogService.class), mock(KnowledgeIndexTaskMapper.class), restTemplate,
                new NoOpTransactionManager(), "http://localhost:8000", "storage/knowledge"
        );
        return new TestFixture(service, target, restTemplate);
    }

    private record TestFixture(
            KnowledgePublicationServiceImpl service,
            KnowledgeDocVersion target,
            RestTemplate restTemplate
    ) {
    }
}
