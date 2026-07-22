package com.aimall.server.service.impl;

import com.aimall.server.entity.KnowledgeDocVersion;
import com.aimall.server.mapper.KnowledgeDocVersionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

@Component
public class KnowledgeStorageReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeStorageReconciliationJob.class);
    private static final Duration ORPHAN_GRACE_PERIOD = Duration.ofHours(24);

    private final KnowledgeDocVersionMapper versionMapper;
    private final Path storageRoot;

    public KnowledgeStorageReconciliationJob(
            KnowledgeDocVersionMapper versionMapper,
            @Value("${aimall.knowledge.storage-root:storage/knowledge}") String storageRoot
    ) {
        this.versionMapper = versionMapper;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    @Scheduled(cron = "${aimall.knowledge.storage-reconcile-cron:0 20 3 * * *}")
    public void removeOrphanFiles() {
        Path originalRoot = storageRoot.resolve("original").normalize();
        if (!originalRoot.startsWith(storageRoot) || !Files.isDirectory(originalRoot)) {
            return;
        }
        Instant cutoff = Instant.now().minus(ORPHAN_GRACE_PERIOD);
        try (Stream<Path> paths = Files.walk(originalRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> isOlderThan(path, cutoff))
                    .forEach(this::deleteIfUnreferenced);
        } catch (Exception exception) {
            log.error("Knowledge storage reconciliation failed", exception);
        }
    }

    private boolean isOlderThan(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
        } catch (Exception exception) {
            return false;
        }
    }

    private void deleteIfUnreferenced(Path path) {
        String normalizedPath = path.toAbsolutePath().normalize().toString();
        long references = versionMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeDocVersion>()
                        .eq(KnowledgeDocVersion::getStoragePath, normalizedPath)
        );
        if (references > 0) {
            return;
        }
        try {
            Files.deleteIfExists(path);
            log.warn("Removed orphan knowledge file: {}", normalizedPath);
        } catch (Exception exception) {
            log.error("Failed to remove orphan knowledge file: {}", normalizedPath, exception);
        }
    }
}
