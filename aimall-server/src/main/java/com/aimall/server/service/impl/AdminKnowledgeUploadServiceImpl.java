package com.aimall.server.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.aimall.server.entity.KnowledgeDoc;
import com.aimall.server.entity.KnowledgeDocVersion;
import com.aimall.server.entity.KnowledgeIndexTask;
import com.aimall.server.mapper.KnowledgeDocMapper;
import com.aimall.server.mapper.KnowledgeDocVersionMapper;
import com.aimall.server.service.AdminKnowledgeUploadService;
import com.aimall.server.service.KnowledgeDocAuditLogService;
import com.aimall.server.service.KnowledgeIndexTaskService;
import com.aimall.server.service.KnowledgeTaskDispatcher;
import com.aimall.server.service.KnowledgeTaskEventService;
import com.aimall.server.security.UploadSecurityScanner;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

@Service
public class AdminKnowledgeUploadServiceImpl implements AdminKnowledgeUploadService {

    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;

    private final KnowledgeDocMapper docMapper;
    private final KnowledgeDocVersionMapper versionMapper;
    private final KnowledgeIndexTaskService taskService;
    private final KnowledgeTaskEventService eventService;
    private final KnowledgeDocAuditLogService auditLogService;
    private final KnowledgeTaskDispatcher taskDispatcher;
    private final UploadSecurityScanner uploadSecurityScanner;
    private final Path storageRoot;

    public AdminKnowledgeUploadServiceImpl(
            KnowledgeDocMapper docMapper,
            KnowledgeDocVersionMapper versionMapper,
            KnowledgeIndexTaskService taskService,
            KnowledgeTaskEventService eventService,
            KnowledgeDocAuditLogService auditLogService,
            KnowledgeTaskDispatcher taskDispatcher,
            UploadSecurityScanner uploadSecurityScanner,
            @Value("${aimall.knowledge.storage-root:storage/knowledge}") String storageRoot
    ) {
        this.docMapper = docMapper;
        this.versionMapper = versionMapper;
        this.taskService = taskService;
        this.eventService = eventService;
        this.auditLogService = auditLogService;
        this.taskDispatcher = taskDispatcher;
        this.uploadSecurityScanner = uploadSecurityScanner;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    @Override
    public Map<String, Object> checkUpload(String fileName, Long fileSize, String sha256) {
        String fileType = fileType(fileName);
        validateFileType(fileType);
        if (fileSize != null && fileSize > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件大小不能超过 20MB，请拆分后上传");
        }
        KnowledgeDocVersion duplicate = null;
        if (sha256 != null && !sha256.isBlank()) {
            duplicate = versionMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeDocVersion>()
                            .eq(KnowledgeDocVersion::getSourceHash, sha256.trim().toLowerCase(Locale.ROOT))
                            .last("LIMIT 1")
            );
        }
        return Map.of(
                "allowUpload", true,
                "duplicate", duplicate != null,
                "reuseDocId", duplicate == null ? "" : duplicate.getDocId(),
                "reuseVersionId", duplicate == null ? "" : duplicate.getId(),
                "fileType", fileType,
                "maxFileSize", MAX_FILE_SIZE
        );
    }

    @Override
    @Transactional
    public Map<String, Object> upload(MultipartFile file, Map<String, String> metadata) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的知识库文档");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件大小不能超过 20MB，请拆分后上传");
        }
        String originalFileName = safeFileName(file.getOriginalFilename());
        String fileType = fileType(originalFileName);
        validateFileType(fileType);
        uploadSecurityScanner.scan(file, fileType);
        String sourceHash = sha256(file);
        LocalDateTime now = LocalDateTime.now();

        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setTitle(valueOrDefault(metadata.get("title"), stripExtension(originalFileName)));
        doc.setSourceType(valueOrDefault(metadata.get("sourceType"), "POLICY"));
        doc.setContent("");
        doc.setStatus("UPLOADED");
        doc.setVersion(1);
        doc.setSourceSystem("admin_upload");
        doc.setSourceHash(sourceHash);
        doc.setVisibilityScope(valueOrDefault(metadata.get("visibilityScope"), "PUBLIC_USER"));
        String tenantId = valueOrDefault(metadata.get("tenantId"), "default");
        if (!"default".equals(tenantId)) {
            throw new IllegalArgumentException("当前部署为单租户模式，拒绝非默认 tenantId");
        }
        doc.setTenantId(tenantId);
        doc.setRoleScope(emptyToNull(metadata.get("roleScope")));
        doc.setCategoryIds(emptyToNull(metadata.get("categoryIds")));
        doc.setTags(emptyToNull(metadata.get("tags")));
        doc.setOwnerUserId(adminId());
        doc.setCreatedBy(adminId());
        doc.setUpdatedBy(adminId());
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        docMapper.insert(doc);

        Path versionDir = storageRoot.resolve("original").resolve(String.valueOf(doc.getId())).resolve("1").normalize();
        if (!versionDir.startsWith(storageRoot)) {
            throw new IllegalStateException("非法存储路径");
        }
        Path targetFile = versionDir.resolve(originalFileName).normalize();
        try {
            Files.createDirectories(versionDir);
            if (!targetFile.startsWith(versionDir)) {
                throw new IllegalStateException("非法文件名");
            }
            file.transferTo(targetFile);

            KnowledgeDocVersion version = new KnowledgeDocVersion();
            version.setDocId(doc.getId());
            version.setVersionNo(1);
            version.setFileName(originalFileName);
            version.setFileType(fileType);
            version.setFileSize(file.getSize());
            version.setSourceHash(sourceHash);
            version.setStoragePath(targetFile.toString());
            version.setStatus("DRAFT");
            version.setPageCount(0);
            version.setParagraphCount(0);
            version.setTableCount(0);
            version.setImageCount(0);
            version.setPiiCount(0);
            version.setPromptRiskLevel("LOW");
            version.setCreatedAt(now);
            version.setUpdatedAt(now);
            versionMapper.insert(version);

            doc.setCurrentVersionId(version.getId());
            docMapper.updateById(doc);

            KnowledgeIndexTask task = taskService.createUploadDocTask(doc.getId(), version.getId(), "AUTO");
            eventService.record(task.getTaskId(), "upload_received", "接收文件", originalFileName, 1, 10, true, null, null);
            eventService.record(task.getTaskId(), "validation_started", "开始基础校验", "校验文件类型、大小和指纹", 2, 10, true, null, null);
            eventService.record(task.getTaskId(), "validation_passed", "基础校验通过", fileType + " / " + file.getSize() + " bytes", 3, 10, true, null, null);
            eventService.record(task.getTaskId(), "parse_started", "等待解析", "后续由 ai-service 解析 PDF/DOCX 并生成结构化节点", 4, 10, true, null, null);
            auditLogService.record(doc.getId(), null, "UPLOAD", null, doc, "admin upload knowledge document");
            taskDispatcher.dispatchAfterCommit(task.getTaskId());

            return Map.of(
                    "docId", doc.getId(),
                    "versionId", version.getId(),
                    "taskId", task.getTaskId(),
                    "status", task.getStatus(),
                    "fileType", fileType,
                    "sourceHash", sourceHash
            );
        } catch (Exception exception) {
            deleteQuietly(targetFile);
            throw new RuntimeException("保存知识库文档失败：" + exception.getMessage(), exception);
        }
    }

    @Override
    @Transactional
    public Map<String, Object> uploadVersion(Long docId, MultipartFile file) {
        KnowledgeDoc doc = docMapper.selectById(docId);
        if (doc == null) {
            throw new IllegalArgumentException("知识库文档不存在");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的新版本文件");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件大小不能超过 20MB，请拆分后上传");
        }
        String originalFileName = safeFileName(file.getOriginalFilename());
        String fileType = fileType(originalFileName);
        validateFileType(fileType);
        uploadSecurityScanner.scan(file, fileType);
        String sourceHash = sha256(file);
        KnowledgeDocVersion duplicate = versionMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocVersion>()
                        .eq(KnowledgeDocVersion::getDocId, docId)
                        .eq(KnowledgeDocVersion::getSourceHash, sourceHash)
                        .last("LIMIT 1")
        );
        if (duplicate != null) {
            throw new IllegalArgumentException("该文件与版本 V" + duplicate.getVersionNo() + " 内容完全相同，无需重复上传");
        }
        KnowledgeDocVersion latest = versionMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocVersion>()
                        .eq(KnowledgeDocVersion::getDocId, docId)
                        .orderByDesc(KnowledgeDocVersion::getVersionNo)
                        .last("LIMIT 1")
        );
        int versionNo = latest == null ? 1 : latest.getVersionNo() + 1;
        LocalDateTime now = LocalDateTime.now();
        Path versionDir = storageRoot.resolve("original").resolve(String.valueOf(docId)).resolve(String.valueOf(versionNo)).normalize();
        if (!versionDir.startsWith(storageRoot)) {
            throw new IllegalStateException("非法存储路径");
        }
        Path targetFile = versionDir.resolve(originalFileName).normalize();
        try {
            Files.createDirectories(versionDir);
            if (!targetFile.startsWith(versionDir)) {
                throw new IllegalStateException("非法文件名");
            }
            file.transferTo(targetFile);
            KnowledgeDocVersion version = new KnowledgeDocVersion();
            version.setDocId(docId);
            version.setVersionNo(versionNo);
            version.setFileName(originalFileName);
            version.setFileType(fileType);
            version.setFileSize(file.getSize());
            version.setSourceHash(sourceHash);
            version.setStoragePath(targetFile.toString());
            version.setStatus("DRAFT");
            version.setPageCount(0);
            version.setParagraphCount(0);
            version.setTableCount(0);
            version.setImageCount(0);
            version.setPiiCount(0);
            version.setPromptRiskLevel("LOW");
            version.setCreatedAt(now);
            version.setUpdatedAt(now);
            versionMapper.insert(version);

            if (doc.getCurrentVersionId() == null) {
                doc.setCurrentVersionId(version.getId());
                doc.setVersion(versionNo);
            }
            doc.setSourceHash(sourceHash);
            doc.setUpdatedBy(adminId());
            doc.setUpdatedAt(now);
            docMapper.updateById(doc);

            KnowledgeIndexTask task = taskService.createUploadDocTask(docId, version.getId(), "AUTO");
            eventService.record(task.getTaskId(), "upload_received", "接收新版本", originalFileName + " / V" + versionNo, 1, 10, true, null, null);
            eventService.record(task.getTaskId(), "validation_started", "开始基础校验", "校验文件类型、大小和指纹", 2, 10, true, null, null);
            eventService.record(task.getTaskId(), "validation_passed", "基础校验通过", fileType + " / " + file.getSize() + " bytes", 3, 10, true, null, null);
            eventService.record(task.getTaskId(), "parse_started", "等待解析", "线上版本保持可用，新版本完成发布后才切换", 4, 10, true, null, null);
            auditLogService.record(docId, null, "UPLOAD_VERSION", null, version, "admin upload knowledge document version V" + versionNo);
            taskDispatcher.dispatchAfterCommit(task.getTaskId());
            return Map.of(
                    "docId", docId,
                    "versionId", version.getId(),
                    "versionNo", versionNo,
                    "taskId", task.getTaskId(),
                    "status", task.getStatus(),
                    "fileType", fileType,
                    "sourceHash", sourceHash
            );
        } catch (Exception exception) {
            deleteQuietly(targetFile);
            throw new RuntimeException("保存知识库新版本失败：" + exception.getMessage(), exception);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // A scheduled storage reconciliation job removes files left by a failed compensation.
        }
    }

    private void validateFileType(String fileType) {
        if (!("PDF".equals(fileType) || "DOCX".equals(fileType) || "MD".equals(fileType) || "TXT".equals(fileType))) {
            throw new IllegalArgumentException("仅支持 PDF、DOCX、Markdown、TXT 文档");
        }
    }

    private String sha256(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new RuntimeException("计算文件指纹失败", exception);
        }
    }

    private String safeFileName(String fileName) {
        String name = fileName == null || fileName.isBlank() ? "knowledge.txt" : Path.of(fileName).getFileName().toString();
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String fileType(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".docx")) return "DOCX";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "MD";
        if (lower.endsWith(".txt")) return "TXT";
        return "UNKNOWN";
    }

    private String stripExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index <= 0 ? fileName : fileName.substring(0, index);
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Long adminId() {
        String loginId = StpUtil.getLoginIdAsString();
        if (loginId != null && loginId.startsWith("admin_")) {
            return Long.parseLong(loginId.substring("admin_".length()));
        }
        return null;
    }
}
